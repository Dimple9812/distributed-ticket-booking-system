# Project summary

A one-page overview: what was built, what was verified, how to talk about it in interviews,
and what is left to do. For the full technical write-up see the [README](../README.md).

## Status
- 73 automated tests (15 unit, 58 integration) pass against real PostgreSQL 16, Redis 7 and a real Kafka broker.
  The final code passed 5 full builds in a row.
- **Not verified:** `docker-compose.yml`, the `Dockerfile` and the Testcontainers path were never executed
  (the build environment had no Docker daemon). The integration tests ran in their fallback mode against
  PostgreSQL and Redis installed directly on the machine. Run `docker compose up` and `mvn verify` on a machine
  with Docker before calling those parts working.
- **Not built:** the React frontend (Stage 9).
- **Load-test target missed:** confirm p95 was 1.10 s against a 1 s target.

## Structure
```
ticket-booking-system/
├── pom.xml  Dockerfile  docker-compose.yml  .env.example  .gitignore  README.md
├── docs/  ARCHITECTURE.md (Mermaid: components, sequence, state machines, ER)
│          DESIGN-DECISIONS.md   PROJECT-SUMMARY.md   postman/ticket-booking.postman_collection.json
├── load-test/  booking-load.js  README.md  results/ (raw k6 output)
└── src/main/java/com/ticketbooking/
      auth booking booking/lock common config event exception idempotency messaging
      notification outbox payment screening seat security user venue
    src/main/resources/  application*.yml  db/migration/V1__initial_schema.sql
    src/test/java/...  15 unit + 9 integration test classes, support/ (infrastructure, latch runner, clock)
```

## Commands
```bash
cp .env.example .env                 # fill in DB_PASSWORD, JWT_SECRET (openssl rand -base64 48), ADMIN_*
docker compose up -d                 # PostgreSQL, Redis, Kafka
set -a; source .env; set +a
mvn spring-boot:run -Dspring-boot.run.profiles=local   # Flyway migrates automatically; seeds sample data
# Swagger: http://localhost:8080/swagger-ui.html
docker compose --profile app up -d --build             # or run everything in Docker
docker compose down                                    # stop (add -v to wipe data)

mvn test     # unit tests
mvn verify   # unit + integration (Testcontainers; or TEST_INFRA=external, see README)
```

## Verified by actually running it
- All 16 mandatory test scenarios have named tests; the README maps each scenario to its test.
- **50-customer race for one seat:** exactly one booking, with or without Redis.
- **Failure injection:**
  - A real PostgreSQL error at the last step of confirmation left no partial booking.
  - A Kafka outage left the outbox event pending and recoverable.
  - Duplicate Kafka deliveries produced no duplicate notifications.
  - Poison messages went to the dead-letter topic.
- **Running jar:** the packaged jar was started against real infrastructure and exercised with
  register → hold → confirm → Kafka → notification. One correlation ID appeared on every log line.
- **Startup checks:** the app refuses to start without `JWT_SECRET`, and there are no committed secrets.
- **Load test (k6, 2-vCPU VM shared with everything):**
  - About 276 hold/confirm attempts per second, with 0 HTTP errors.
  - Exactly 100 bookings for 100 seats.
  - Hold p95 was 0.44 s; confirm p95 was 1.10 s, which missed the 1 s target.

**Two problems the load test exposed, both fixed and covered by tests:**
- **Redis wasn't filtering conflicts.** After a booking, its Redis locks were deleted, so 94% of conflicts were
  only caught by PostgreSQL. Booked seats now keep a "sold" marker in Redis, and Redis now catches 100% of them.
- **The first fix had a race.** It could delete a live hold's lock. PostgreSQL still prevented double booking,
  but the lock should not have been deleted.

## Architecture in one paragraph
**PostgreSQL decides who gets a seat.** Every seat change is a conditional `UPDATE` whose row count is checked.
A partial unique index guarantees at most one *active* booking per seat. Rows are locked in seat-id order, so
overlapping requests can't deadlock.

**Redis is only a fast filter.** Each lock is set with `SET NX` and an expiry, and released only if it still holds
this hold's id. If Redis goes down, the app keeps selling on PostgreSQL alone by default.

**Events use a transactional outbox.** The outbox row commits together with the booking. A publisher marks it
published only after Kafka confirms receipt, and the consumer de-duplicates on `eventId`.

## Interview explanations
- **Concurrency:** "I don't trust any single check. The seat update is `UPDATE … WHERE status='AVAILABLE'`, and I
  require the affected row count to equal the number of requested seats; otherwise the whole transaction rolls back.
  That makes multi-seat holds all-or-nothing. Below that, a unique index on active booking items means PostgreSQL
  rejects a double booking even if my code is wrong; there's a test that bypasses the app to prove it."
- **Redis locking:** "Each lock's value is the hold id, which acts as an ownership token. Release runs a Lua script
  that deletes the key only if it still holds that token, so I can never free another customer's lock. Locks can
  expire or be lost in a failover, so Redis only *rejects* requests early. It never *grants* a seat."
- **Transactions:** "Confirmation is one transaction: lock the hold, check it's mine and unexpired, charge, insert the
  booking, flip seats to BOOKED, write the outbox event, store the idempotency response. Redis cleanup runs only after
  commit, so it never acts on a change that rolled back."
- **Kafka:** "Delivery is at-least-once. Kafka's acknowledgement and my 'published' update are two systems, so a crash
  between them causes a re-send. The consumer records each `eventId` in `processed_events` in the same transaction as
  its side effects, so duplicates do nothing. That makes database effects effectively-once; I don't claim end-to-end
  exactly-once."
- **Idempotency:** "The key is claimed in its own short transaction. The operation and its stored response then commit
  together, so a retry after a timeout either replays the stored response or runs cleanly. It never does both. Reusing
  a key with a different body returns 422. A concurrent duplicate gets 409. A crashed request's claim expires after
  30 s so the client can retry."
- **Failure recovery:**
  - Expiry workers use `SKIP LOCKED` and only release seats `WHERE hold_id = <this hold>`, so a late worker can't free
    a newer hold.
  - Outbox claims expire, so another instance picks up a dead publisher's events.
  - Consumer failures retry three times, then go to the dead-letter topic.

## Resume bullets (all reflect implemented, tested features)
- Built a Spring Boot 3 / Java 21 ticket booking backend that guarantees no double booking under concurrency, using
  PostgreSQL conditional updates, ordered row locks and a partial unique index. Verified by integration tests that race
  50 simultaneous customers for one seat, with and without Redis.
- Implemented Redis seat locks (atomic `SET NX`, release only by the owner via Lua) as a contention filter in front of
  the database. Load testing on a sold-out show showed the database caught 94% of conflicts; adding "sold" markers moved
  that to 100% in Redis, with 0 errors and 100 bookings for 100 seats.
- Delivered reliable asynchronous notifications with a transactional outbox, Kafka (`acks=all`, retries, dead-letter
  topic) and an idempotent consumer, plus durable per-user idempotency keys. Covered by tests that inject Kafka outages,
  publisher crashes, duplicate deliveries and database failures.

## Remaining improvements
- Run the Docker Compose and Testcontainers paths on a machine with Docker.
- Build the React frontend.
- Use an authorise/capture payment flow with compensation (currently the charge happens inside the DB transaction).
- Check Redis before the idempotency claim, to cut database load during a rush.
- Add a waiting room for very hot events.
- Add rate limiting and refresh tokens, and lock down `/actuator/prometheus`.
- Add admin update/cancel endpoints for screenings.
