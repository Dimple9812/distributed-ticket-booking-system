# Load test (k6)

`booking-load.js` simulates a ticket rush on a single screening: many customers fight over a
small pool of seats while others browse the seat map.

```bash
# app running locally with ADMIN_EMAIL / ADMIN_PASSWORD set
k6 run -e BASE_URL=http://localhost:8080 \
       -e ADMIN_EMAIL=admin@example.com -e ADMIN_PASSWORD='<your admin password>' \
       --summary-export load-test/results/summary.json \
       load-test/booking-load.js
```

Tunables (`-e NAME=value`): `BUYER_VUS` (50), `BROWSER_VUS` (10), `DURATION` (60s),
`SEAT_ROWS` (5 rows x 20 = 100 seats), `CUSTOMERS` (200).

## Workload
* **setup** – admin creates a venue (100 seats), an event, a screening, prices; registers 200 customers.
* **buyers** – 50 VUs in a closed loop, no think time: pick a random seat → `POST /holds` →
  if 201, `POST /bookings/confirm`. Every request uses a fresh `Idempotency-Key`.
  All 100 seats sell out within the first seconds; for the rest of the minute almost every hold is a
  409 conflict, which is exactly the "rush on a sold-out show" path.
* **browsers** – 10 VUs repeatedly reading `GET /screenings/{id}/seats`.
* **teardown** – prints the seat map summary; the invariant is `BOOKED == bookings_confirmed`.

## Measured results (21 Oct 2026)

**Environment.** One VM, 2 vCPU (Intel Xeon @ 2.10 GHz), 8 GB RAM, shared by *everything*: the
application (single instance, default JVM settings, Hikari pool 20), PostgreSQL 16, Redis 7,
a single-node Kafka 3.9.2 broker, and the k6 load generator itself. No Docker. These numbers describe
this box only; they are not a capacity claim for any production setup.

| Run | Build | Iterations in 60 s | Hold p50 / p95 / p99 | Confirm p50 / p95 / p99 | HTTP errors | Conflicts rejected by Redis / PostgreSQL |
|---|---|---|---|---|---|---|
| 1 | locks deleted after booking | 16,306 (~272/s) | 209 / 468 / 667 ms | 523 / 946 / 1,124 ms | 0 | 767 / 11,647 |
| 3 | "sold" markers kept in Redis | 16,555 (~276/s) | 197 / 439 / 695 ms | 575 / 1,100 / 1,390 ms | 0 | 12,636 / 0 |

In both runs: **100 seats, exactly 100 confirmed bookings, 0 seats with more than one active booking item**
(checked with SQL after the run), 0 unexpected errors, outbox backlog drained to 0, one notification per booking.

Notes, honestly stated:
* The confirm latency threshold (`p95 < 1 s`) **was crossed in run 3** (1.10 s). Only 100 confirms happen,
  all in the first seconds when contention and CPU pressure are highest, so this percentile is noisy.
* An intermediate run 2 (18,521 iterations) used a first version of stale-marker "healing" that could delete a
  *live* hold's Redis lock under a race (the database still prevented double booking). It was found from the
  `Removed stale Redis lock` warnings in the logs, fixed, and covered by a test; its numbers are not used above.
* Run-to-run variance on this shared 2-vCPU machine is roughly ±10 %. The main effect of the sold markers is
  where conflicts are detected (Redis instead of PostgreSQL row locks), not raw throughput: each rejected
  request still pays for authentication and the idempotency claim in PostgreSQL. Checking Redis before the
  idempotency claim is listed as a future improvement.

Raw k6 summaries: `results/summary-run1.json`, `results/summary-run3.json`, `results/k6-output-run3.txt`.
