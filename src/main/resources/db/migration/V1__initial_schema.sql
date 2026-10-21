-- =====================================================================
-- Distributed Ticket Booking System - initial schema
-- All timestamps are TIMESTAMPTZ and written by the application in UTC.
-- PostgreSQL is the source of truth for seat allocation; every
-- correctness invariant that matters is enforced here, not only in code.
-- =====================================================================

-- Needed for the exclusion constraint that prevents overlapping screenings
-- in the same venue (btree_gist is a "trusted" extension since PG 13).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);

-- ---------------------------------------------------------------------
-- Venues and physical seats (a seat belongs to a venue, not a screening)
-- ---------------------------------------------------------------------
CREATE TABLE venues (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(150) NOT NULL,
    city       VARCHAR(100) NOT NULL,
    address    VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_venues_name_city UNIQUE (name, city)
);

CREATE TABLE seats (
    id          BIGSERIAL PRIMARY KEY,
    venue_id    BIGINT      NOT NULL REFERENCES venues (id),
    row_label   VARCHAR(5)  NOT NULL,
    seat_number INT         NOT NULL,
    seat_type   VARCHAR(20) NOT NULL,
    CONSTRAINT uq_seats_position UNIQUE (venue_id, row_label, seat_number),
    CONSTRAINT ck_seats_number CHECK (seat_number > 0),
    CONSTRAINT ck_seats_type CHECK (seat_type IN ('STANDARD', 'PREMIUM', 'VIP'))
);

-- ---------------------------------------------------------------------
-- Events and screenings (a screening = one event at one venue/time)
-- ---------------------------------------------------------------------
CREATE TABLE events (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(200)  NOT NULL,
    description      VARCHAR(2000),
    category         VARCHAR(30)   NOT NULL,
    language         VARCHAR(40),
    duration_minutes INT           NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_events_duration CHECK (duration_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_events_category CHECK (category IN ('MOVIE', 'CONCERT', 'THEATRE', 'SPORTS', 'COMEDY', 'OTHER'))
);
CREATE INDEX ix_events_category ON events (category);

CREATE TABLE screenings (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    venue_id   BIGINT      NOT NULL REFERENCES venues (id),
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    status     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_screenings_time CHECK (ends_at > starts_at),
    CONSTRAINT ck_screenings_status CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    -- A venue cannot host two scheduled screenings whose time ranges overlap.
    CONSTRAINT ex_screenings_no_overlap EXCLUDE USING gist (
        venue_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status = 'SCHEDULED')
);
CREATE INDEX ix_screenings_event_starts ON screenings (event_id, starts_at);

-- ---------------------------------------------------------------------
-- Seat holds: temporary reservations with an expiry.
--   ACTIVE -> CONFIRMED | RELEASED | EXPIRED   (all terminal)
-- ---------------------------------------------------------------------
CREATE TABLE seat_holds (
    id           UUID PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    screening_id BIGINT      NOT NULL REFERENCES screenings (id),
    status       VARCHAR(20) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_seat_holds_status CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED'))
);
CREATE INDEX ix_seat_holds_user ON seat_holds (user_id, created_at DESC);
-- Partial index used by the expiry worker: only ACTIVE holds are scanned.
CREATE INDEX ix_seat_holds_active_expiry ON seat_holds (expires_at) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- Bookings: created only when a hold is confirmed (and paid).
--   CONFIRMED -> CANCELLED (terminal)
-- ---------------------------------------------------------------------
CREATE TABLE bookings (
    id                UUID PRIMARY KEY,
    user_id           BIGINT        NOT NULL REFERENCES users (id),
    screening_id      BIGINT        NOT NULL REFERENCES screenings (id),
    hold_id           UUID          NOT NULL REFERENCES seat_holds (id),
    status            VARCHAR(20)   NOT NULL,
    total_amount      NUMERIC(12, 2) NOT NULL,
    currency          VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    payment_reference VARCHAR(100)  NOT NULL,
    confirmed_at      TIMESTAMPTZ   NOT NULL,
    cancelled_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    -- One hold can produce at most one booking, ever.
    CONSTRAINT uq_bookings_hold UNIQUE (hold_id),
    CONSTRAINT ck_bookings_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_bookings_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_bookings_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX ix_bookings_user_created ON bookings (user_id, created_at DESC);

-- ---------------------------------------------------------------------
-- Per-screening seat inventory. Exactly one row per (screening, seat).
--   AVAILABLE -> HELD -> BOOKED -> AVAILABLE (on cancellation)
--   HELD -> AVAILABLE (release / expiry)
-- The CHECK constraint makes illegal combinations unrepresentable.
-- ---------------------------------------------------------------------
CREATE TABLE screening_seats (
    id           BIGSERIAL PRIMARY KEY,
    screening_id BIGINT        NOT NULL REFERENCES screenings (id),
    seat_id      BIGINT        NOT NULL REFERENCES seats (id),
    price        NUMERIC(10, 2) NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    hold_id      UUID REFERENCES seat_holds (id),
    held_until   TIMESTAMPTZ,
    booking_id   UUID REFERENCES bookings (id),
    version      BIGINT        NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_screening_seats UNIQUE (screening_id, seat_id),
    CONSTRAINT ck_screening_seats_price CHECK (price > 0),
    CONSTRAINT ck_screening_seats_state CHECK (
        (status = 'AVAILABLE' AND hold_id IS NULL AND held_until IS NULL AND booking_id IS NULL)
     OR (status = 'HELD'      AND hold_id IS NOT NULL AND held_until IS NOT NULL AND booking_id IS NULL)
     OR (status = 'BOOKED'    AND booking_id IS NOT NULL AND hold_id IS NULL AND held_until IS NULL)
    )
);
CREATE INDEX ix_screening_seats_hold ON screening_seats (hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX ix_screening_seats_booking ON screening_seats (booking_id) WHERE booking_id IS NOT NULL;

CREATE TABLE seat_hold_items (
    hold_id           UUID   NOT NULL REFERENCES seat_holds (id),
    screening_seat_id BIGINT NOT NULL REFERENCES screening_seats (id),
    PRIMARY KEY (hold_id, screening_seat_id)
);

CREATE TABLE booking_items (
    id                BIGSERIAL PRIMARY KEY,
    booking_id        UUID          NOT NULL REFERENCES bookings (id),
    screening_seat_id BIGINT        NOT NULL REFERENCES screening_seats (id),
    price             NUMERIC(10, 2) NOT NULL,
    active            BOOLEAN       NOT NULL,
    CONSTRAINT uq_booking_items UNIQUE (booking_id, screening_seat_id)
);
-- THE final allocation invariant: at most one *active* booking item per
-- screening seat. Even if every application-level check were bypassed,
-- PostgreSQL rejects a second active booking of the same seat.
CREATE UNIQUE INDEX ux_booking_items_active_seat ON booking_items (screening_seat_id) WHERE active;

-- ---------------------------------------------------------------------
-- Simulated payments (never trusts a client-supplied status)
-- ---------------------------------------------------------------------
CREATE TABLE payment_attempts (
    id                 UUID PRIMARY KEY,
    user_id            BIGINT        NOT NULL REFERENCES users (id),
    hold_id            UUID          NOT NULL REFERENCES seat_holds (id),
    booking_id         UUID REFERENCES bookings (id),
    amount             NUMERIC(12, 2) NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    provider_reference VARCHAR(100),
    failure_reason     VARCHAR(300),
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_payment_attempts_status CHECK (status IN ('SUCCEEDED', 'DECLINED', 'REFUNDED'))
);
CREATE INDEX ix_payment_attempts_hold ON payment_attempts (hold_id);
CREATE INDEX ix_payment_attempts_booking ON payment_attempts (booking_id) WHERE booking_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Durable idempotency records, scoped per user.
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id              UUID PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(100) NOT NULL,
    operation       VARCHAR(50)  NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    response_status INT,
    response_body   TEXT,
    locked_until    TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_completed CHECK (
        (status = 'COMPLETED') = (response_status IS NOT NULL AND completed_at IS NOT NULL)
    )
);
CREATE INDEX ix_idempotency_expires ON idempotency_records (expires_at);

-- ---------------------------------------------------------------------
-- Transactional outbox: written in the same transaction as the business
-- change, published to Kafka asynchronously (at-least-once).
-- ---------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    event_version   INT          NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    payload         JSONB        NOT NULL,
    correlation_id  VARCHAR(64),
    status          VARCHAR(20)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    claimed_by      VARCHAR(100),
    claimed_until   TIMESTAMPTZ,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at, created_at) WHERE status = 'PENDING';
-- Supports the per-aggregate ordering check in the publisher's claim query.
CREATE INDEX ix_outbox_pending_aggregate ON outbox_events (aggregate_id, created_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- Consumer-side de-duplication and the notification read model.
-- ---------------------------------------------------------------------
CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    event_id   UUID         NOT NULL,
    type       VARCHAR(50)  NOT NULL,
    channel    VARCHAR(20)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    message    VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_notifications_event_channel UNIQUE (event_id, channel)
);
CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
