# failure-propagation-lab

**Series 3 - Failure Propagation in Microservices**

Experiments that show how a failure in one service becomes an outage in services that never
touch it. Series 2 (`backpressure-playground`) bounded a single service; Series 3 wires bounded
and unbounded services into topologies and watches what travels between them.

All experiments run from the repo root via Gradle tasks - no shell scripts required.
Topic tasks are the stable API; `runPostN` tasks are compatibility aliases for article-order
references. Each run writes CSV files, PNG charts, a per-run `manifest.json`, and a
self-contained `report.html` that opens directly from `file://`.

---

## These are lab experiments, not production measurements

Like Series 2, every number here is produced by running the deterministic models in this module,
and the golden-file tests re-run them and assert the output byte-for-byte. The models are
deliberately simple and **synthetic**: bounded worker pools with fixed service times, deterministic
arrivals, synchronous calls, no network. Per ADR-007, the golden contract for every Series 3 post
is this synthetic simulation; **Javalin live mode (the same topology over real localhost HTTP)
arrives in Post 3**, where interactively tripping a circuit breaker carries the lesson - live-mode
output is demonstrative, never golden-tested.

What transfers to production is the mechanism, not the numbers:

- a cascade needs no failure at all - a slow dependency, synchronous calls, and a *shared bounded
  worker pool* are sufficient, and the blast crosses to routes that never touch the slow service
  (Post 1);
- the backlog stacks *upstream* of the bottleneck: the slow service's own queue can read near
  zero while its callers drown (Post 1);
- per-hop retries compound multiplicatively - R attempts at each of d hops is up to R^d attempts
  at the bottom; a retry is a bet that the failure is transient, rescuing real clients when it
  is and buying pure load when it is not (Post 2).

---

## Prerequisites

- Java 25 (JDK 25+)
- Gradle (wrapper included - `./gradlew`)

---

## Post 1: Cascading Failures Explained

**TL;DR**
- A cascade is resource coupling, not data coupling: route-b shares nothing with the slow
  database except the frontend's worker pool, and that alone kills it
- The mechanism: a synchronous call holds the caller's worker for the entire downstream wait,
  so a slow leaf parks every pool between it and the client until a *shared* pool is exhausted
- The backlog piles up upstream of the bottleneck - the database's own queue stays at zero
  while the frontend's grows without bound; queue depth at the slow service is the wrong alarm

No timeouts and no retries in this model, by design: retries are Post 2, timeout budgets are
Post 4, and bulkheads (the cure) are Post 5 - here a control test simply proves isolation
contains the blast.

### Run it

```bash
./gradlew :failure-propagation-lab:runCascadingFailures -Pargs="--deterministic --duration 5s --output-dir ./results/cascading-failures"
```

The model is a fully synthetic, single-threaded discrete-event simulation, so output is
byte-for-byte reproducible. `--concurrency` and `--snapshot-interval` are accepted for CLI
consistency but do not affect this experiment.

### What it models

Four services, two routes. `frontend` (pool 20, 5ms) serves route-a = frontend -> `service-a`
(pool 10, 5ms) -> `database` (pool 10) and route-b = frontend -> `service-b` (pool 10, 10ms).
Each route gets 50 rps; the client deadline is 1000ms. A request holds one worker at every hop
until the leaf completes. Two experiments:

1. **The sweep** - the database's service time is held constant per point and swept from 10ms
   to 500ms across its capacity edge (10 workers / 200ms = 50 rps = route-a's demand).
2. **The timeline (the hero)** - the database degrades mid-run (10ms -> 500ms at t=2s); route
   success and every service's queue depth are sampled per 100ms window.

### Expected golden output (deterministic, 5s)

Sweep (database capacity edge at 200ms):

| DB service (ms) | route-a ok% | route-a p99 | route-b ok% | route-b p99 |
|----------------:|------------:|------------:|------------:|------------:|
| 10 | 100.0 | 20 | 100.0 | 15 |
| 150 | 100.0 | 160 | 100.0 | 15 |
| 200 | 100.0 | 305 | 100.0 | 15 |
| 250 | 69.7 | 1250 | **100.0** | 820 |
| 300 | 34.8 | 1885 | **64.5** | 1510 |
| 500 | 10.0 | 2951 | **24.5** | 2575 |

Read route-b's column: its own services are healthy at every point, yet past the database's
capacity edge its success rate collapses - at 500ms it completes a quarter of its work. The only
coupling is the shared frontend pool. In the timeline, the database degrades at t=2s: route-a's
success hits 0 by ~2.4s, route-b follows by ~3.2s once waiting route-a work exhausts the
frontend's 20 workers, and the queue columns show the propagation - `frontend_queue` grows
0 -> 91 while `database_queue` reads 0 in every window (service-a's pool gates what reaches it;
the victims queue upstream).

### Output files

| File | Contents |
|---|---|
| `fp-post1-cascade-sweep.csv` | One row per (db service time, route) - golden contract |
| `fp-post1-cascade-timeline.csv` | Per-100ms-window route success + per-service queue depth - golden contract |
| `fp-post1-cascade-sweep.png` | Success vs db service time: route-b collapses despite never touching it |
| `fp-post1-cascade-timeline.png` | Both routes dying through the mid-run degradation |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `cascade/ServiceConfig.java` | One service: bounded worker pool + unbounded FIFO queue |
| `cascade/ServiceTime.java` | Service time as a function of simulated time (constant / degradedAfter) |
| `cascade/RouteDemand.java` | Client demand walking a linear synchronous call chain |
| `cascade/CascadeSimulator.java` | Deterministic event loop; synchronous holds are the cascade vector |
| `cascade/CascadeScenario.java` | The degradation sweep and the mid-run degradation timeline |
| `charting/CascadeChartGenerator.java` | XChart PNGs for the sweep and timeline charts |
| `CascadingFailuresMain.java` | Entry point: CSVs, charts, manifest, report |

---

## Post 2: Retry Storms and Amplification

**TL;DR**
- Per-hop retries compound multiplicatively: R attempts at each retrying hop becomes up to
  R^2 attempts at the bottom of a two-edge chain (R^d for depth d) - one client request,
  nine database attempts
- A retry is a bet that the failure is transient: against a 1s blip the bet wins (clients held
  at ~100% where no-retry reads 0%) at a 6x database-load spike; against a hard-down dependency
  the bet loses completely - R^2 load, zero successes
- The caller that times out walks away, but its callee doesn't know and keeps retrying -
  abandoned work is what turns a slowdown into a storm

Series 2 Post 1 showed *client* retries amplifying load on a single server; Post 2's new thing
is the mesh doing it to itself, multiplicatively, hop by hop. The per-call timeout here is
deliberately uniform (400ms everywhere) - a caller gives up while its callee's own ~1300ms
retry budget keeps running. Why and how to coordinate timeouts is Post 4's lesson.

### Run it

```bash
./gradlew :failure-propagation-lab:runRetryStorms -Pargs="--deterministic --duration 5s --output-dir ./results/retry-storms"
```

The model is a fully synthetic, single-threaded discrete-event simulation, so output is
byte-for-byte reproducible. `--concurrency` and `--snapshot-interval` are accepted for CLI
consistency but do not affect this experiment.

### What it models

A three-service chain - `frontend` -> `service-a` -> `database` - with 50 rps of client demand
and no client retry. Both non-leaf hops apply the same naive policy: per-call timeout 400ms,
retry on timeout or failure, 50ms backoff, R attempts. The upper tiers are deliberately
over-provisioned (pools of 200; Post 1 covered what under-provisioned shared pools do), so the
amplified load reaches the database unthrottled. Two experiments:

1. **The amplification sweep** - R in {1,2,3,4} against a healthy (10ms) and a hard-down
   (500ms, deliberately past the 400ms timeout) database. Amplification is measured over
   requests old enough for their full retry tree to finish inside the window.
2. **The storm timeline** - the database degrades transiently (1.5s to 2.5s) and recovers;
   client success and database attempt rate are sampled per 100ms window for R=1 vs R=3.

### Expected golden output (deterministic, 5s)

Amplification sweep:

| Mode | R | Success% | DB attempts/request | DB attempts rps | p99 resolution |
|---|--:|---------:|--------------------:|----------------:|---------------:|
| healthy | 1-4 | 100.0 | 1.00 | 50 | 20 |
| hard-down | 1 | 0.0 | 1.00 | 50 | 405 |
| hard-down | 2 | 0.0 | **4.00** | 182 | 855 |
| hard-down | 3 | 0.0 | **9.00** | 369 | 1305 |
| hard-down | 4 | 0.0 | **14.55** | 379 | 1755 |

Healthy rows are identical at every R: a retry that never fires costs nothing. Hard-down rows
show the compounding - exactly R^2 at R=2 and R=3, and the R=4 bend is signal, not noise: the
amplified load saturates even a 200-worker middle tier, which then throttles the storm itself.
The success column never moves off zero; the p99 column is the time clients wait to be told
nothing (the full retry budget, 405ms -> 1755ms).

Storm timeline: the no-retry run fails hard for ~600ms of the 1s blip (six 0% windows) and its
database load never moves off 50 rps. The R=3 run fails only the two onset windows - requests
caught at the wavefront pay the full exploration cost - then holds ~100% through the rest of
the degradation. The price: database attempts climb 50 -> 150 -> 300 rps (6x) and stay there
for ~700ms. Retries rescued the transient and would have hammered a sustained failure with the
same enthusiasm; nothing in the policy can tell those situations apart. That discrimination is
the circuit breaker's job - Post 3.

### Output files

| File | Contents |
|---|---|
| `fp-post2-amplification-sweep.csv` | One row per (mode, attempts-per-hop) - golden contract |
| `fp-post2-storm-timeline.csv` | Per-100ms-window success + db attempt rate, R=1 vs R=3 - golden contract |
| `fp-post2-storm-timeline.png` | Database attempt rate through the blip: the 6x spike |
| `fp-post2-rescue.png` | Client success through the blip: the won bet |
| `fp-post2-amplification.png` | DB attempts per request vs R: flat at 1 healthy, ~R^2 hard-down |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `retrystorm/RetryPolicy.java` | Attempts per hop, per-call timeout, backoff - retry on any error |
| `retrystorm/RetryStormSimulator.java` | Call-tree event loop; abandoned callers keep retrying |
| `retrystorm/RetryStormScenario.java` | The amplification sweep and the transient-degradation timeline |
| `charting/StormChartGenerator.java` | XChart PNGs for the storm, rescue, and amplification charts |
| `RetryStormsMain.java` | Entry point: CSVs, charts, manifest, report |
