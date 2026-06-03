# backpressure-playground

**Series 2 - Backpressure & Load Control**

Experiments that show why systems collapse under load and how admission control keeps them
alive. Series 1 (`latency-lab`) measured how bad latency gets; Series 2 builds the machinery
that bounds it.

All experiments run from the repo root via Gradle tasks - no shell scripts required.
Topic tasks are the stable API; `runPostN` tasks are compatibility aliases for article-order
references. Each run writes a CSV file, PNG charts, a per-run `manifest.json`, and a
self-contained `report.html` that opens directly from `file://`.

---

## Prerequisites

- Java 25 (JDK 25+)
- Gradle (wrapper included - `./gradlew`)

---

## Post 1: Why Systems Collapse Under Load

**TL;DR**
- Past capacity, an unmanaged service does not plateau at its limit - goodput falls off a cliff
- The cause: with no admission control, the server keeps processing requests whose clients
  already gave up, burning capacity on work that delivers nothing
- Client retries make it worse, multiplying load on an already-failing service without adding
  any goodput - the retry-storm death spiral

This post demonstrates the *problem*. Admission control (the fix) is Series 2, Post 2.

### Run it

```bash
./gradlew :backpressure-playground:runLoadCollapse -Pargs="--deterministic --duration 5s --output-dir ./results/load-collapse"
```

The model is a fully synthetic, single-threaded discrete-event simulation, so output is
byte-for-byte reproducible. `--concurrency` and `--snapshot-interval` are accepted for CLI
consistency but do not affect this experiment (it sweeps offered-load levels, not time).

### What it models

A single server with fixed capacity (mu = 100 rps), an effectively unbounded inbound queue,
and a 200ms client deadline. Offered load is swept from 0.25x to 3x capacity, twice - once
without client retries, once with a 3-retry policy. The crucial modelling choice: the server
advances its busy clock on **every** admitted request, including ones already past their
deadline. A request served after its client gave up still consumed a full service slot but
delivers nothing - that is what turns overload into collapse.

### Expected golden output (deterministic, 5s window)

| Offered | Ideal goodput | Goodput (no backpressure) | Effective load with retries |
|--------:|--------------:|--------------------------:|----------------------------:|
| 100 rps | 100.0 rps | 100.0 rps | 100.0 rps |
| 110 rps | 100.0 rps | 42.0 rps | 281.2 rps |
| 150 rps | 100.0 rps | 11.6 rps | 520.4 rps |
| 200 rps | 100.0 rps | 7.8 rps | 716.6 rps |
| 300 rps | 100.0 rps | 5.8 rps | 1092.6 rps |

At and below capacity, goodput tracks offered load and no work is wasted. Past capacity,
goodput collapses far below the 100 rps the server could sustain, while retries drive the
effective load on the server up to ~3.6x the offered load with no goodput in return.

### Output files

All files land in `--output-dir` (default `./results`):

| File | Contents |
|---|---|
| `bp-post1-collapse-sweep.csv` | One row per (mode, offered-load) point - the golden contract |
| `bp-post1-goodput.png` | Goodput vs offered load: ideal plateau vs the collapse cliff |
| `bp-post1-amplification.png` | Effective load vs offered load: the retry storm |
| `manifest.json` | Run receipt: flags, runtime, CSV SHA-256 values, golden-match status |
| `report.html` | Self-contained HTML report with embedded CSV data |

### CSV schema

```
mode,offered_rps,effective_rps,ideal_goodput_rps,goodput_rps,wasted_pct,p50_ms,p99_ms,avg_queue_depth
```

This is a sweep schema (one row per offered-load level), distinct from Series 1's per-second
ADR-005 snapshot schema. Golden files live in `golden/bp-post1/`; the CSV is the numeric
contract, PNGs are visual references.

### Key source files

| File | Role |
|---|---|
| `collapse/CollapseSimulator.java` | Deterministic discrete-event model; encodes service-then-discard and retry re-injection |
| `collapse/CollapseScenario.java` | Offered-load sweep with the tuned model constants |
| `collapse/LoadLevelResult.java` | Per-level aggregate (goodput, effective load, waste, latency) |
| `charting/CollapseChartGenerator.java` | XChart PNGs for the cliff and the retry storm |
| `LoadCollapseMain.java` | Entry point: CSV, charts, manifest, report |

---

## Post 2: Admission Control Design

**TL;DR**
- The fix for Post 1's collapse is a concurrency limit: cap requests in flight and reject the rest
  at the door, so the server never wastes a slot on work whose client already gave up
- Sizing the limit is the design act: too low rejects burst traffic the server could have absorbed
  in the next lull (wasted capacity); too high lets the backlog grow until waits blow the deadline
  and goodput collapses again
- The sweet spot is Little's Law: `limit = capacity x deadline`. Here that is `100 x 0.2s = 20`

This post is the *cure* for Post 1. It sweeps the admission limit (not the offered load - that is
Series 1 Post 2's descriptive angle) to find the operating point, then shows the collapse cliff
turned back into a plateau.

### Run it

```bash
./gradlew :backpressure-playground:runAdmissionControl -Pargs="--deterministic --duration 5s --output-dir ./results/admission-control"
```

### What it models

The same fixed-capacity server (mu = 100 rps, 200ms deadline) fronted by a concurrency limit: a
new request is admitted only if fewer than `limit` are already in flight, else rejected
immediately. Two experiments:

1. **Choosing the limit** - a deterministic bursty demand curve (half-capacity valleys, 3x spikes,
   averaging ~133 rps) is run while the limit is swept from 1 to "none". Goodput peaks at the
   Little's-Law limit; a time-varying curve is required for the sweet spot to exist (under flat
   overload a tighter limit is simply better).
2. **The payoff** - offered load is swept with no control versus at limit 20, restoring the Post 1
   cliff to a plateau at capacity.

### Expected golden output (deterministic, 5s)

Limit sweep (bursty demand ~133 rps offered, capacity 100):

| Limit | Goodput | Reject% | Served-late% | p99 (ms) | Utilization% |
|------:|--------:|--------:|-------------:|---------:|-------------:|
| 1 | 60.2 | 54.2 | 0.0 | 10 | 60.2 |
| 8 | 88.4 | 32.7 | 0.0 | 80 | 88.4 |
| **20** | **99.8** | 24.0 | 0.0 | 200 | 99.8 |
| 28 | 29.2 | 22.8 | 54.9 | 280 | 100.0 |
| none | 12.0 | 0.0 | 90.9 | 1750 | 100.0 |

Goodput peaks at limit 20 (`capacity x deadline`) and falls off sharply once the worst-case wait
crosses the 200ms deadline. Offered-load plateau (limit 20 vs no control): no control collapses
from 100 to 19.2 / 7.8 / 5.8 rps at 125 / 200 / 300 rps offered, while admission control holds
goodput at ~capacity (the slight overshoot to ~104 rps is in-flight work draining just past the
run window) with reject% rising gracefully and p99 capped at the deadline.

### Output files

| File | Contents |
|---|---|
| `bp-post2-limit-sweep.csv` | One row per admission limit over the bursty curve - golden contract |
| `bp-post2-plateau.csv` | Goodput vs offered load, no control vs admission-limited - golden contract |
| `bp-post2-limit-sweep.png` | Goodput + utilization vs limit: the sweet-spot curve |
| `bp-post2-plateau.png` | Goodput vs offered load: collapse cliff restored to a plateau |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `admission/AdmissionSimulator.java` | Single-server FIFO + concurrency-limit admission (fail-fast reject) |
| `admission/DemandCurve.java` | Deterministic piecewise-constant offered-load schedule (bursty/constant) |
| `admission/AdmissionScenario.java` | The limit sweep and the offered-load plateau experiments |
| `charting/AdmissionChartGenerator.java` | XChart PNGs for the sweet-spot and plateau charts |
| `AdmissionControlMain.java` | Entry point: CSVs, charts, manifest, report |
