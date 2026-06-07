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
  zero while its callers drown (Post 1).

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
