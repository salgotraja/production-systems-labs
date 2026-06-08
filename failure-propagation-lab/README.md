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

---

## Post 3: Circuit Breaker Design

**TL;DR**
- A circuit breaker cannot buy successes against a real outage - success stays 0% with or
  without it; what it buys is cheap failures (median 105ms fail-fast vs a 1305ms hang), an
  unharassed dependency (Post 2's 9.00 attempts/request collapses to 0.29), and the survival
  of routes that share resources with the failing one
- The blast radius is the headline: naive retries hold the shared frontend pool for 1305ms per
  request and kill route-b for seven windows; with breakers, route-b never drops a single
  window - the trip truncates in-flight retry chains and frees the pool
- Recovery is one nested probe pass: the frontend edge's single trial request traverses the
  chain, drives the database edge's probe, and re-closes both breakers together, 15ms apart -
  and the retries above the breaker bridge its last open moments

The breaker never learns where the failure is - it breaks on observed edge health. That is why
one breaker at the top edge also stops the storm three hops down, and why the same state
machine works whether the slow thing is its callee or its callee's callee.

### Run it

```bash
./gradlew :failure-propagation-lab:runCircuitBreaker -Pargs="--deterministic --duration 5s --output-dir ./results/circuit-breaker"
```

And the live mode (ADR-007's promise: the same `CircuitBreaker` class, wall clock, real HTTP):

```bash
./gradlew :failure-propagation-lab:runCircuitBreakerLive
# in another terminal:
curl localhost:7070/checkout                      # ok in ~15ms
curl -X POST "localhost:7071/degrade?ms=800"
curl localhost:7070/checkout                      # 922ms of retries... breaker OPEN
curl localhost:7070/checkout                      # 503 in ~107ms - fail fast
curl -X POST "localhost:7071/degrade?ms=10"
sleep 5; curl localhost:7070/checkout             # the probe closes it: ok in ~17ms
```

Live mode is demonstrative only - never golden-tested, never run in CI.

### What it models

The hand-rolled [`CircuitBreaker`](src/main/java/dev/engnotes/labs/failprop/breaker/CircuitBreaker.java)
is one page of arithmetic: a count-based sliding window (last 20 outcomes, minimum 10), a 50%
failure-rate trip, a 500ms open period, and a single half-open probe. Time is passed in
explicitly, so the same class runs under the simulation clock and the live mode's wall clock.
Two experiments:

1. **The hard-down comparison** - Post 2's exact scenario (single chain, R=3, database at
   500ms vs the 400ms timeout) with no protection, with hand-rolled breakers, and with real
   Resilience4j breakers driven through a synthetic-clock adapter.
2. **The blast-radius timeline (the hero)** - Post 1's topology (shared frontend, pool 20,
   route-b never touches the database) + Post 2's retry policy + a transient degradation,
   naive vs breakered. Route-b has a 300ms interactive budget; a 1000ms deadline would forgive
   a full second of shared-pool queueing and hide the cascade entirely.

### Expected golden output (deterministic, 5s)

Hard-down comparison:

| Policy | Success% | DB attempts/request | DB attempts rps | p50 resolution | p99 resolution |
|---|---------:|--------------------:|----------------:|---------------:|---------------:|
| naive-retry | 0.0 | 9.00 | 369.4 | 1305 | 1305 |
| with-breaker | 0.0 | **0.29** | **9.6** | **105** | 905 |
| resilience4j | 0.0 | **0.29** | **9.6** | **105** | 905 |

The naive row is Post 2's golden row, reproduced exactly (the cross-check). The breaker rows:
the storm collapses 38x, the median failure is a fast 105ms instead of the full 1305ms retry
budget, and the success column never moves - a breaker is not a source of availability, it is
a manager of failure cost. The Resilience4j row is byte-identical to the hand-rolled one:
once the state machine is understood, the library holds no surprises.

Blast-radius timeline: the naive run's route-b - which never touches the database - fails for
seven windows (five at 0%) starting ~1.8s, as 1305ms route-a holds exhaust the 20-worker
frontend pool. The breakered run's route-b **never drops below 100% in any window**: the edges
trip at ~2.09s, in-flight retry chains truncate, and the pool drains in time. The database
columns show 0 attempts while the breakers are open (the naive run keeps hammering at 100-150
rps), and the state columns record the whole arc: closed -> open at ~2.09s -> half-open at
~2.59s -> closed at 2.60s, both edges re-closed by one nested probe.

### Output files

| File | Contents |
|---|---|
| `fp-post3-breaker-sweep.csv` | One row per policy on the hard-down chain - golden contract |
| `fp-post3-blast-radius.csv` | Per-100ms-window route success, db attempts, breaker states - golden contract |
| `fp-post3-blast-radius.png` | Route-b survival: the breaker contains Post 1's cascade |
| `fp-post3-storm-suppression.png` | Post 2's storm, suppressed to a probe trickle |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `breaker/CircuitBreaker.java` | The hand-rolled state machine (deep-dive material) |
| `breaker/BreakerConfig.java` | Threshold, window, open duration, probe budget |
| `breaker/EdgeBreaker.java` | What the simulator needs from a breaker on one edge |
| `breaker/Resilience4jBreakerAdapter.java` | The real library under the synthetic clock |
| `breaker/BreakerStormSimulator.java` | Post 2's machine + routes + breaker gates + fail-fast responses |
| `breaker/BreakerStormScenario.java` | The hard-down comparison and the blast-radius timeline |
| `CircuitBreakerLiveMain.java` | The Javalin live mode - trip it with curl |

---

## Post 4: Timeout Budgeting

**TL;DR**
- A propagated deadline is the latency dial: the request carries an absolute budget, every hop
  refuses to start - or keep running - a call that cannot finish in time, so p99 tracks the
  deadline exactly (450ms at a 400ms deadline, 1000ms at a 1000ms deadline) where an
  uncoordinated per-call timeout leaves it flat at 1305ms
- A breaker bounds the dependency's *load*, not any single request's *latency*: across the whole
  deadline sweep its p99 sits at ~905ms regardless of how long the client is willing to wait
- The headline: a deadline tighter than one retry-width (timeout 400 + backoff 50 = 450ms)
  admits a single attempt, so the retry storm never forms. A 500ms call can never beat a 400ms
  deadline (the slow 40% are doomed either way), but dropping the database from a 6.34x-amplified
  flood to one attempt per request stops the eligible fast calls from drowning in retry queue, so
  the healthy 60% succeed - and because the budget acts on the very first request with no warmup,
  at a tight deadline it beats the breaker (60.6% vs 39.4% success)

This is the fix for the uncoordinated timeout Posts 2 and 3 kept flagging: a 400ms caller giving
up while its callee's ~1300ms retry budget ran on. The deadline now travels *with* the request,
so even an abandoned subtree self-terminates at it.

### Run it

```bash
./gradlew :failure-propagation-lab:runTimeoutBudgets -Pargs="--deterministic --duration 5s --output-dir ./results/timeout-budgets"
```

The model is a fully synthetic, single-threaded discrete-event simulation, so output is
byte-for-byte reproducible.

### What it models

Post 3's chain and breaker exactly (frontend -> service-a -> database, R=3, 400ms timeout, a
50%/20-call breaker), but the database is *partially* degraded: a fixed-seed 40% of calls are
slow (500ms, past the timeout), the rest healthy. A real outage is the breaker's job (Post 3);
partial degradation is the hard case, because the slow minority still storms while the majority
is fine. The new control is a propagated `TimeoutBudget` (deadline + floor). Two experiments:

1. **The deadline sweep (the hero)** - the client deadline is swept across one retry-width for
   three policies (no protection, breaker only, budget only).
2. **The tight-deadline table** - at the 400ms deadline, all four combinations head to head.

### Expected golden output (deterministic, 5s)

Deadline sweep (success% / p99 resolution ms):

| Deadline | no-protection | breaker | budget |
|---:|---|---|---|
| 400 | 13.4% / 1305 | 39.4% / 905 | **60.6% / 450** |
| 450 | 13.6% / 1305 | 39.9% / 905 | **61.0% / 455** |
| 500 | 13.7% / 1305 | 40.3% / 905 | 19.0% / 550 |
| 1000 | 16.4% / 1305 | 48.3% / 905 | 16.4% / 1000 |

Read the budget column's p99: it *is* the deadline (450, 455, 550, ... 1000) - the dial. The
other two are flat (1305 unprotected, 905 breaker), because neither bounds a single request's
latency. And the success knee at 450ms is the storm threshold: below one retry-width the budget
admits one attempt and the storm cannot form (60% succeed); at 500ms a retry fits, the storm
returns, and success collapses to ~19%.

Tight-deadline table (400ms):

| Policy | Success% | DB att/req | Past-deadline% | p50 | p99 |
|---|--:|--:|--:|--:|--:|
| no-protection | 13.4 | 6.34 | 84.2 | 1305 | 1305 |
| breaker | 39.4 | 0.79 | 25.4 | 105 | 905 |
| budget | **60.6** | 1.00 | **0.0** | 180 | **450** |
| budget+breaker | 49.8 | 0.75 | 0.0 | 105 | 450 |

At a tight deadline the budget alone wins: it prevents the storm (1.00 db attempt/request, zero
work past the deadline) and serves the healthy majority. Adding the breaker actually *costs*
success here (49.8 vs 60.6) - it sheds requests the single-attempt budget would have served. The
breaker earns its place at *loose* deadlines, where the storm forms despite budgeting and its
load relief is the only thing that helps. The tools are complementary, not ranked: the deadline
is the first-line dial; the breaker is for when you cannot tighten it.

### Output files

| File | Contents |
|---|---|
| `fp-post4-deadline-sweep.csv` | One row per (deadline, policy) - golden contract |
| `fp-post4-policy-table.csv` | Four policies at the 400ms deadline - golden contract |
| `fp-post4-deadline-sweep.png` | Success vs deadline: the dial and the budget/breaker crossover |
| `fp-post4-latency-cap.png` | p99 vs deadline: budget tracks it, the others are flat |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `breaker/TimeoutBudget.java` | The propagated deadline (budget + floor) |
| `breaker/BreakerStormSimulator.java` | Post 3's machine + the deadline gate and timeout cap |
| `cascade/ServiceTime.java` | `partialDegradation` - a fixed-seed slow fraction |
| `budget/BudgetScenario.java` | The deadline sweep and the tight-deadline table |
| `charting/BudgetChartGenerator.java` | XChart PNGs for the dial and the latency cap |
| `TimeoutBudgetsMain.java` | Entry point: CSVs, charts, manifest, report |

---

## Post 5: Failure Isolation Boundaries (the capstone)

**TL;DR**
- The failure mode no detection-based tool can touch: a *slow-but-healthy* neighbour. route-a's
  database is slow but under the timeout, so its calls succeed in ~390ms - nothing fails, nothing
  is slow on route-b's own path - yet route-a's holds saturate the shared frontend pool and
  route-b (which never touches the database) starves from 100% to 22%
- The circuit breaker never trips (no failures) and the timeout budget never fires (route-b's
  loss is the frontend *queue* wait, before any downstream call): both are byte-for-byte inert,
  identical to no protection at all. Only a **bulkhead** - a dedicated slice of the pool reserved
  for route-b - contains the blast, lifting it back to 100%
- That answers Post 1: the cascade is *resource coupling*, not failure propagation, so the fix
  isolates the resource rather than detecting a failure. Size the reserve to the protected
  route's actual need (Little's Law: `λ_b × sojourn_b ≈ 1 worker`); over-reserve and you starve
  the neighbour you were trying to keep useful

This is the series capstone: it assembles the four tools (retry, breaker, budget, bulkhead)
and shows they are complementary, each for its own failure mode - and that the bulkhead is the
one that addresses the root cause Post 1 named.

### Run it

```bash
./gradlew :failure-propagation-lab:runFailureIsolation -Pargs="--deterministic --duration 5s --output-dir ./results/failure-isolation"
```

The model is a fully synthetic, single-threaded discrete-event simulation, so output is
byte-for-byte reproducible (constant service times, no randomness).

### What it models

A 20-worker frontend pool shared by two routes with different SLAs: route-a (a slow batch
endpoint, frontend -> service-a -> database with the database at 380ms, just under the 400ms
timeout) on a loose 1000ms deadline at 55 rps; route-b (a fast interactive endpoint,
frontend -> service-b at ~15ms) on a tight 100ms deadline at 50 rps. route-a's offered
concurrency (`55 × 0.39 ≈ 21`) exceeds the pool, so it saturates the shared queue and route-b
starves behind 390ms holds. The database has 30 workers so it never saturates - route-a's calls
succeed, which is the whole point: there is no failure to detect. Two experiments:

1. **The policy table** - naive, breaker, budget, bulkhead, and all combined.
2. **The bulkhead sizing sweep** - route-b's reserved slice from 1 to 8 workers.

### Expected golden output (deterministic, 5s)

Policy table:

| Policy | route-a ok% | route-a p99 | route-b ok% | route-b p99 |
|---|--:|--:|--:|--:|
| naive | 100.0 | 795 | **22.0** | 490 |
| breaker | 100.0 | 795 | **22.0** | 490 |
| budget | 100.0 | 795 | **22.0** | 490 |
| bulkhead | 100.0 | 880 | **100.0** | 15 |
| all-three | 100.0 | 880 | **100.0** | 15 |

The breaker and budget rows are *identical* to naive, to the decimal: with nothing failing and
nothing slow on route-b's own path, they have nothing to act on. Only the bulkhead lifts route-b
- and at its correctly-sized one-worker reserve it does so at no cost to route-a (still 100%,
p99 nudged 795 -> 880 as route-a trades its 20th shared worker for 19 dedicated ones).

Bulkhead sizing sweep:

| route-b reserved | route-a workers | route-a ok% | route-b ok% |
|--:|--:|--:|--:|
| 1 | 19 | **100.0** | 100.0 |
| 2 | 18 | 81.8 | 100.0 |
| 4 | 16 | 50.9 | 100.0 |
| 8 | 12 | 21.8 | 100.0 |

route-b is whole from one reserved worker - its entire need - so reserving more buys it nothing
and costs route-a everything: the cost of isolation is the idle slack route-a can no longer
borrow. Size the bulkhead to the protected route's need, not bigger.

### Output files

| File | Contents |
|---|---|
| `fp-post5-policy-table.csv` | Five policies, both routes scored - golden contract |
| `fp-post5-bulkhead-sizing.csv` | route-b's reserve from 1 to 8 workers - golden contract |
| `fp-post5-policy.png` | Success by policy: only the bulkhead lifts the victim |
| `fp-post5-bulkhead-sizing.png` | The sizing trade-off: route-b flat, route-a falling |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `breaker/BreakerStormSimulator.java` | Per-route partition admission (the bulkhead) + per-route deadlines |
| `bulkhead/BulkheadScenario.java` | The policy comparison and the sizing sweep |
| `charting/BulkheadChartGenerator.java` | XChart PNGs for the policy bars and the sizing trade-off |
| `FailureIsolationMain.java` | Entry point: CSVs, charts, manifest, report |
