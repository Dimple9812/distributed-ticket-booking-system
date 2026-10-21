# Design decisions

Short records of the choices that shape this system, why they were made, and what they cost.

## 1. PostgreSQL is the only authority for seat allocation
**Decision.** Every seat state change is a conditional `UPDATE` in PostgreSQL
(`... WHERE status = 'HELD' AND hold_id = :hold AND held_until > :now`) whose row count is checked.
The final invariant is a partial unique index: `UNIQUE (screening_seat_id) WHERE active` on `booking_items`,
plus a `CHECK` constraint that makes illegal seat states unrepresentable.

**Why.** Redis locks can expire mid-request, be lost in a failover, or be unavailable. A correctness
guarantee that depends on them is not a guarantee. The database already provides transactions,
row locks and constraints.

**Cost.** Every hold and confirmation touches PostgreSQL rows under lock; throughput for a single hot
screening is bounded by row-lock contention on those seats.

## 2. Redis locks are a contention filter, not a safety mechanism
**Decision.** `SET seat-lock:{screening}:{seat} <holdId> NX PX <ttl>` per seat, acquired in ascending seat
order, released with a Lua compare-and-delete. Locks live as long as the hold (5 min).

**Why.** Under a ticket rush most requests are for seats that are already taken. Rejecting them in Redis
(sub-millisecond, no DB connection) protects the database. The hold id doubles as the ownership token, so
release/expiry code can only ever delete its own lock.

After a booking commits, the hold's locks are converted (compare-and-set) into sold markers
`booked:<bookingId>` that live until the screening ends; cancellation removes them. The first load test showed why:
when locks were simply deleted after booking, 94 % of conflicts on a sold-out screening were only caught by
PostgreSQL; with sold markers, 100 % were caught by Redis. A sold marker that outlives its booking (failed
cleanup) is detected against PostgreSQL on the next conflict and removed; locks of live holds are never removed
that way, because a concurrent reader can see a hold that is not yet committed.

**Failure modes.** `DB_ONLY` (default): if Redis is down, holds continue with PostgreSQL-only protection —
still correct, just more DB contention. `FAIL_CLOSED`: reject holds with 503. A *false* Redis conflict
(e.g. clock skew between app and Redis) can only cause an unnecessary 409, never a double booking.

## 3. Holds and bookings are separate aggregates
**Decision.** A `seat_holds` row (ACTIVE → CONFIRMED | RELEASED | EXPIRED) represents the pending purchase.
A `bookings` row is created only on successful confirmation (CONFIRMED → CANCELLED).
`bookings.hold_id` is `UNIQUE`, so one hold can never become two bookings.

**Why.** It keeps the booking table free of abandoned carts and gives each lifecycle a tiny, closed state
machine that is easy to enforce with conditional updates and constraints.

## 4. Expired holds can be taken over without waiting for the worker
**Decision.** The hold update accepts `status = 'AVAILABLE' OR (status = 'HELD' AND held_until <= now)`.
The expiry worker is for cleanup, events and Redis hygiene — not a prerequisite for re-selling a seat.

**Why.** If the worker is down or lagging, seats must not stay unsellable. Because the worker's release is
`WHERE hold_id = :thatHold`, a late worker can never free a seat that a newer hold took over.

## 5. Deterministic lock ordering
Both Redis keys and PostgreSQL row locks (`SELECT ... ORDER BY id FOR UPDATE`) are taken in ascending
seat id order. Two requests for overlapping seat sets therefore can't deadlock, which is verified by a
test racing `[1,2]`, `[2,3]`, `[3,1]` requests.

## 6. Idempotency is durable and atomic with the business change
**Decision.** `(user_id, idempotency_key)` unique records. Phase 1 claims the key in a short transaction
(`INSERT ... ON CONFLICT DO NOTHING`). Phase 2 runs the operation **and** stores the response in the same
transaction. Failures delete the claim so the client can retry; a crashed claim becomes reusable after a
30 s lease. Takeover uses a new record id so a zombie request can't complete someone else's claim.

**Why.** Storing the response in a separate step would leave a window where the booking committed but the
"completed" marker did not — a retry would then re-execute. Only successful responses are stored, so a
transient failure never poisons a key.

## 7. Transactional outbox for events, at-least-once delivery
**Decision.** Booking changes and their outbox rows commit together (`OutboxWriter` requires an existing
transaction: `Propagation.MANDATORY`). A publisher claims rows with `FOR UPDATE SKIP LOCKED` and a lease,
sends with `acks=all` + idempotent producer, waits for the ack, then marks them `PUBLISHED`.

**Why not publish directly from the service?** "Commit then send" loses events if the process dies in
between; "send then commit" announces bookings that might roll back.

**Duplicates are expected.** "Kafka acknowledged" and "row marked published" are two systems; a crash
between them causes a re-send. Every event has a stable `eventId`; the consumer inserts it into
`processed_events` in the same transaction as its side effects, so a duplicate is a no-op.
This is **effectively-once processing of database effects**, not end-to-end exactly-once.
The mock email is a side effect outside the database and could, in a crash window, be sent twice.

## 8. Consumer failure handling
Transient errors: 3 in-process attempts with exponential back-off (offset not committed meanwhile).
Poison messages (bad JSON, unknown type/version): no retry, straight to `booking-events.DLT`.
Exhausted retries: DLT. Offsets are committed only after successful processing or DLT publication.

## 9. Payments are simulated and server-decided
The client sends a payment-method token (`pm_card_visa`, `pm_card_declined`, ...); a simulated gateway
decides. There is no client-supplied "paid" field. Declined attempts are recorded in an independent
transaction so the audit row survives the booking rollback.

**Known gap.** With a real provider, charging inside the DB transaction is wrong: if the transaction rolls
back after a successful charge, money was taken without a booking. A production design would
authorise → commit booking → capture, with a compensating void/refund (a small saga).

## 10. A modular monolith, not microservices
One deployable with clear packages (`booking`, `outbox`, `notification`, ...). The notification consumer
already talks to the rest only through Kafka, so it can be extracted later. Splitting earlier would add
network failure modes without a scaling need.

## 11. Time comes from an injected `Clock`
All deadlines (hold expiry, cancellation cut-off, JWT validity, idempotency retention) use one UTC `Clock`
bean. Tests replace it with a controllable clock, so expiry scenarios are deterministic and sleep-free.

## 12. Schema owned by Flyway
`spring.jpa.hibernate.ddl-auto=validate`: Hibernate checks the entities match the migrated schema and
never changes it. The app process runs as a non-superuser; the only extension (`btree_gist`, for the
no-overlapping-screenings exclusion constraint) is a trusted extension.
