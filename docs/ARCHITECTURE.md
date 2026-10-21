# Architecture

## Component view

```mermaid
flowchart LR
    client([Client / Postman / k6]) -->|HTTPS + JWT| api

    subgraph app["Spring Boot application (modular monolith)"]
        api[REST controllers<br/>auth · events · screenings · holds · bookings · admin]
        idem[IdempotencyService]
        hold[HoldService]
        book[BookingService]
        pay[Simulated PaymentGateway]
        lock[SeatLockService]
        expiry[HoldExpiryService<br/>scheduled]
        outw[OutboxWriter]
        pub[OutboxPublisher<br/>scheduled]
        cons[BookingEventConsumer<br/>+ NotificationService]
        api --> idem --> hold & book
        hold --> lock
        book --> pay
        hold & book & expiry --> outw
    end

    lock <-->|SET NX PX / Lua compare-and-delete| redis[(Redis<br/>seat locks)]
    hold & book & expiry & idem & outw --> pg[(PostgreSQL<br/>source of truth)]
    pub -->|claim rows FOR UPDATE SKIP LOCKED| pg
    pub -->|acks=all, idempotent producer| kafka[[Kafka<br/>booking-events]]
    kafka --> cons
    cons -->|processed_events + notifications| pg
    cons -.->|after retries / poison| dlt[[booking-events.DLT]]
```

## Hold → confirm sequence

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer
    participant A as API
    participant I as Idempotency
    participant R as Redis
    participant P as PostgreSQL
    participant O as OutboxPublisher
    participant K as Kafka

    C->>A: POST /screenings/{id}/holds (Idempotency-Key)
    A->>I: claim key (INSERT ... ON CONFLICT DO NOTHING)
    A->>P: validate screening + seats (reads)
    A->>R: SET seat-lock:{scr}:{seat} holdId NX PX 5m (ascending order)
    A->>P: BEGIN; SELECT seats ORDER BY id FOR UPDATE
    A->>P: INSERT seat_holds; UPDATE screening_seats SET HELD WHERE AVAILABLE (rowcount must = N)
    A->>P: UPDATE idempotency_records SET COMPLETED, response; COMMIT
    A-->>C: 201 holdId, expiresAt

    C->>A: POST /bookings/confirm (holdId, paymentMethodToken, Idempotency-Key)
    A->>P: BEGIN; SELECT hold FOR NO KEY UPDATE (owner check, ACTIVE, not expired)
    A->>A: simulated charge (server decides)
    A->>P: INSERT bookings + booking_items (partial unique index), payment_attempts
    A->>P: UPDATE screening_seats SET BOOKED WHERE hold_id=? AND HELD AND held_until>now
    A->>P: UPDATE seat_holds CONFIRMED; INSERT outbox_events; idempotency COMPLETED; COMMIT
    A->>R: after commit: compare-and-set locks to sold markers booked:{bookingId}
    A-->>C: 201 booking

    O->>P: claim PENDING rows (lease, SKIP LOCKED)
    O->>K: send, wait for ack
    O->>P: mark PUBLISHED
```

## State machines

```mermaid
stateDiagram-v2
    state "Seat (per screening)" as seat {
        [*] --> AVAILABLE
        AVAILABLE --> HELD: hold
        HELD --> HELD: takeover of an expired hold
        HELD --> BOOKED: confirm
        HELD --> AVAILABLE: release / expiry
        BOOKED --> AVAILABLE: cancel
    }
    state "Hold" as h {
        [*] --> ACTIVE
        ACTIVE --> CONFIRMED
        ACTIVE --> RELEASED
        ACTIVE --> EXPIRED
    }
    state "Booking" as b {
        [*] --> CONFIRMED
        CONFIRMED --> CANCELLED
    }
```

## Entity-relationship diagram

```mermaid
erDiagram
    USERS ||--o{ SEAT_HOLDS : places
    USERS ||--o{ BOOKINGS : owns
    USERS ||--o{ IDEMPOTENCY_RECORDS : sends
    USERS ||--o{ NOTIFICATIONS : receives
    VENUES ||--o{ SEATS : has
    VENUES ||--o{ SCREENINGS : hosts
    EVENTS ||--o{ SCREENINGS : "is shown at"
    SCREENINGS ||--o{ SCREENING_SEATS : "sells"
    SEATS ||--o{ SCREENING_SEATS : "priced per screening"
    SEAT_HOLDS ||--o{ SEAT_HOLD_ITEMS : contains
    SCREENING_SEATS ||--o{ SEAT_HOLD_ITEMS : "requested by"
    SEAT_HOLDS ||--o| BOOKINGS : "confirmed as (hold_id UNIQUE)"
    BOOKINGS ||--o{ BOOKING_ITEMS : contains
    SCREENING_SEATS ||--o{ BOOKING_ITEMS : "booked as (one active)"
    SEAT_HOLDS ||--o{ PAYMENT_ATTEMPTS : "paid via"
    BOOKINGS ||--o{ PAYMENT_ATTEMPTS : "charged / refunded"

    USERS {
        bigint id PK
        varchar email UK
        varchar password_hash
        varchar role
    }
    VENUES {
        bigint id PK
        varchar name
        varchar city
    }
    SEATS {
        bigint id PK
        bigint venue_id FK
        varchar row_label
        int seat_number
        varchar seat_type
    }
    EVENTS {
        bigint id PK
        varchar title
        varchar category
        int duration_minutes
    }
    SCREENINGS {
        bigint id PK
        bigint event_id FK
        bigint venue_id FK
        timestamptz starts_at
        timestamptz ends_at
        varchar status
    }
    SCREENING_SEATS {
        bigint id PK
        bigint screening_id FK
        bigint seat_id FK
        numeric price
        varchar status
        uuid hold_id FK
        timestamptz held_until
        uuid booking_id FK
    }
    SEAT_HOLDS {
        uuid id PK
        bigint user_id FK
        bigint screening_id FK
        varchar status
        timestamptz expires_at
    }
    SEAT_HOLD_ITEMS {
        uuid hold_id PK
        bigint screening_seat_id PK
    }
    BOOKINGS {
        uuid id PK
        bigint user_id FK
        uuid hold_id UK
        varchar status
        numeric total_amount
        varchar payment_reference
    }
    BOOKING_ITEMS {
        bigint id PK
        uuid booking_id FK
        bigint screening_seat_id FK
        numeric price
        boolean active
    }
    PAYMENT_ATTEMPTS {
        uuid id PK
        uuid hold_id FK
        uuid booking_id FK
        varchar status
        varchar provider_reference
    }
    IDEMPOTENCY_RECORDS {
        uuid id PK
        bigint user_id FK
        varchar idempotency_key
        char request_hash
        varchar status
        text response_body
    }
    OUTBOX_EVENTS {
        uuid id PK
        varchar aggregate_id
        varchar event_type
        int event_version
        jsonb payload
        varchar status
        int attempts
    }
    PROCESSED_EVENTS {
        varchar consumer_group PK
        uuid event_id PK
    }
    NOTIFICATIONS {
        bigint id PK
        bigint user_id FK
        uuid event_id
        varchar channel
    }
```

### Constraints that carry the correctness guarantees

| Constraint | Guarantees |
|---|---|
| `uq_screening_seats (screening_id, seat_id)` | one inventory row per seat per screening |
| `ck_screening_seats_state` | HELD ⇔ hold_id + held_until set; BOOKED ⇔ booking_id set; AVAILABLE ⇔ nothing set |
| `ux_booking_items_active_seat` (partial unique, `WHERE active`) | **at most one active booking per screening seat** |
| `uq_bookings_hold (hold_id)` | a hold becomes at most one booking |
| `ex_screenings_no_overlap` (GiST exclusion) | a venue never has overlapping scheduled screenings |
| `uq_idempotency_user_key (user_id, idempotency_key)` | one claim per key per user |
| `processed_events PK (consumer_group, event_id)` | each event's side effects applied once per consumer group |
