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
