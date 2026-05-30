# latency-lab

**Series 1 — Tail Latency & System Behavior**

Six experiments that show how latency distributions, queueing dynamics, and measurement errors
shape the reliability of real production systems.

All experiments run from the repo root via Gradle tasks — no shell scripts required.
Topic tasks are the stable API; `runPostN` tasks are compatibility aliases for article-order references.
Each run writes CSV files, PNG charts, a per-run `manifest.json`, and a self-contained
CSV/manifest `report.html` that opens directly from `file://`.

---

## Prerequisites

- Java 25 (JDK 25+)
- Gradle (wrapper included — `./gradlew`)

---

## Post 1: Why Average Latency Lies

**TL;DR**
- p50 (median) looks healthy even when p99 is on fire
- Fan-out to N downstream services amplifies tail probability from 1% to ~5% with just 5 deps
- Run the lab yourself: a 5-second deterministic experiment reproduces the numbers exactly

### Run it

```bash
# Quick deterministic run (reproducible synthetic event loop)
./gradlew :latency-lab:runTailLatency -Pargs="--deterministic --duration 5s --concurrency 10 --output-dir ./results/tail-latency --snapshot-interval 1s"

# Full 30-second live run
./gradlew :latency-lab:runTailLatency -Pargs="--duration 30s --concurrency 100 --output-dir ./results/tail-latency"
```

### What it measures

| Scenario | Description |
|---|---|
| **Baseline** | Single service, bimodal distribution (99% fast ~20ms, 1% tail 200–3000ms) |
| **Tail Amplification** | Same service fans out to 5 downstream deps; top-level latency = max of all 5 |

### Expected golden output (deterministic, 5s, concurrency=10)

```
Baseline:         p50=  20.0ms  p99=  34.0ms  p99.9=1383.0ms
Tail-Amplified:   p50=  26.0ms  p99= 597.0ms  p99.9=1849.0ms
p99 amplification factor: 17.6x
```

The baseline p99 is 34ms in the final deterministic snapshot. After fan-out, a 1% tail per downstream service compounds:
`P(at least one of 5 hits tail) = 1 - (1-0.01)^5 ≈ 4.9%` — which means the p99 of the
top-level request absorbs what was previously only the p95 of each downstream.

### Output files

All files land in `--output-dir` (default `./results`):

| File | Contents |
|---|---|
| `post1-baseline.csv` | ADR-005 snapshot rows for the normal distribution scenario |
| `post1-tail-amplification.csv` | ADR-005 snapshot rows for the fan-out scenario |
| `post1-baseline.png` | p50 / p99 / p99.9 over time — normal scenario |
| `post1-tail-amplification.png` | p50 / p99 / p99.9 over time — fan-out scenario |
| `post1-comparison.png` | Overlay: Normal p99 vs Fan-Out p99 on one chart |
| `manifest.json` | Run receipt: flags, runtime, CSV SHA-256 values, golden-match status |
| `report.html` | Self-contained HTML report with embedded CSV data |

### CSV schema (ADR-005)

```
timestamp_ms,elapsed_s,p50_ms,p95_ms,p99_ms,p999_ms,throughput_rps,error_count,total_requests
```

Columns are append-only — never reordered or removed.

### Key source files

| File | Role |
|---|---|
| `simulation/LatencyInjector.java` | Bimodal distribution sampler (normal + exponential tail) |
| `simulation/RequestSimulator.java` | Runs concurrent virtual clients via `ScopedRunner.fanOut` |
| `simulation/TailAmplificationScenario.java` | Fan-out to 5 services; records max latency |
| `charting/LatencyChartGenerator.java` | XChart PNG output |
| `TailLatencyMain.java` | Entry point — wires CLI args, runs both scenarios, writes output |

---

## Post 2: Queueing Theory for Engineers

```bash
./gradlew :latency-lab:runQueueSaturation -Pargs="--deterministic --duration 2s --concurrency 1 --output-dir ./results/queue-saturation --snapshot-interval 1s"
```

Outputs:

| File | Contents |
|---|---|
| `post2-saturation.csv` | Utilization sweep with throughput, latency, queue depth, and Little's Law fields |
| `post2-throughput.png` | Target RPS vs actual RPS |
| `post2-latency.png` | p50 / p99 / p99.9 vs utilization |
| `post2-littles-law.png` | Measured queue depth vs computed λW |

Expected deterministic summary:

```
rho=1.0 p99=10.0ms, mean sojourn=10.0ms
rho=1.05 p99=109.0ms, mean sojourn=59.8ms
rho=1.1 p99=208.0ms, mean sojourn=109.5ms
rho=1.2 p99=407.0ms, mean sojourn=209.2ms
rho=1.3 p99=605.0ms, mean sojourn=308.8ms
```

## Post 3: Hedged Requests & Speculative Execution

```bash
./gradlew :latency-lab:runHedgedRequests -Pargs="--deterministic --duration 5s --concurrency 10 --output-dir ./results/hedged-requests --snapshot-interval 1s --hedge-threshold p95"
```

Expected deterministic summary:

```
p95 hedge: baseline p99=200.0ms, hedged p99=43.0ms, extra load=3.7%
```

Outputs:

| File | Contents |
|---|---|
| `post3-baseline.csv` | Baseline latency snapshots with no hedging |
| `post3-hedged-p95.csv` | Hedged latency snapshots using the selected threshold |
| `post3-cost-table.csv` | p90 / p95 / p99 threshold trade-off table |
| `post3-latency-comparison.png` | Baseline p99 vs hedged p99 |
| `post3-cost-table.png` | p99 improvement % vs extra load % |

## Post 4: The Coordinated Omission Problem

```bash
./gradlew :latency-lab:runCoordinatedOmission -Pargs="--deterministic --duration 5s --concurrency 10 --output-dir ./results/coordinated-omission --snapshot-interval 1s --measurement-mode both"
```

Expected deterministic summary:

```
closed-loop raw p99=10.0ms, open-loop p99=460.0ms, corrected p99=460.0ms
```

Outputs:

| File | Contents |
|---|---|
| `post4-closed-loop-raw.csv` | Closed-loop raw snapshots |
| `post4-closed-loop-corrected.csv` | Closed-loop snapshots corrected for coordinated omission |
| `post4-open-loop.csv` | Open-loop snapshots |
| `post4-summary.csv` | Whole-run p99 and request counts |
| `post4-p99-comparison.png` | Closed-loop raw vs corrected vs open-loop p99 |
| `post4-throughput.png` | Closed-loop throughput drop vs open-loop throughput |

## Post 5: Backpressure Design Patterns

```bash
./gradlew :latency-lab:runBackpressure -Pargs="--deterministic --duration 5s --concurrency 10 --output-dir ./results/backpressure --snapshot-interval 1s --backpressure-strategy all"
```

Expected deterministic summary:

```
token-bucket accepted=599 rejected=401 p99=10.0ms
bounded-queue accepted=549 rejected=451 p99=500.0ms
leaky-bucket accepted=524 rejected=476 p99=250.0ms
load-shedder accepted=500 rejected=500 p99=50.0ms
resilience4j-rate-limiter accepted=500 rejected=500 p99=10.0ms
```

Outputs:

| File | Contents |
|---|---|
| `post5-backpressure-summary.csv` | Strategy-level accepted/rejected counts, p50, p99, max buffer |
| `post5-backpressure-snapshots.csv` | Per-second accepted/rejected/latency rows per strategy |
| `post5-acceptance.png` | Accepted vs rejected requests |
| `post5-latency.png` | Accepted request p50 / p99 by strategy |

## Post 6: SLO Engineering

```bash
./gradlew :latency-lab:runSloPolicy -Pargs="--deterministic --duration 5s --concurrency 10 --output-dir ./results/slo-policy --snapshot-interval 1s --slo-target p99<200ms"
```

Expected deterministic summary:

```
baseline:          SLI=57.36%, bad=2132, worst burn=100.00x, budget remaining=0.00%
bulkhead:          SLI=99.60%, bad=20,   worst burn=1.00x,   budget remaining=60.00%
timeout-budget:    SLI=57.36%, bad=2132, worst burn=100.00x, budget remaining=0.00%
concurrency-limit: SLI=99.20%, bad=40,   worst burn=2.00x,   budget remaining=20.00%
```

Outputs:

| File | Contents |
|---|---|
| `post6-slo-summary.csv` | Per-pattern SLI, bad events, burn rate, remaining budget, alert state |
| `post6-slo-windows.csv` | Per-second p99, bad-event %, burn rate, remaining budget, alert state |
| `post6-burn-rate.png` | Instantaneous burn rate over time |
| `post6-error-budget.png` | Achieved SLI and remaining budget by pattern |
