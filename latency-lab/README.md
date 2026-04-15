# latency-lab

**Series 1 — Tail Latency & System Behavior**

Six experiments that show how latency distributions, queueing dynamics, and measurement errors
shape the reliability of real production systems.

All experiments run from the repo root via Gradle tasks — no shell scripts required.

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
# Quick deterministic run (reproducible, uses Thread.sleep)
./gradlew :latency-lab:runPost1 -Pargs="--deterministic --duration 5s --concurrency 10 --output-dir ./results/post1 --snapshot-interval 1s"

# Full 30-second live run
./gradlew :latency-lab:runPost1 -Pargs="--duration 30s --concurrency 100 --output-dir ./results/post1"
```

### What it measures

| Scenario | Description |
|---|---|
| **Baseline** | Single service, bimodal distribution (99% fast ~20ms, 1% tail 200–3000ms) |
| **Tail Amplification** | Same service fans out to 5 downstream deps; top-level latency = max of all 5 |

### Expected golden output (deterministic, 5s, concurrency=10)

```
Baseline:         p50=  20.0ms  p99=  31.0ms  p99.9=  35.0ms
Tail-Amplified:   p50= ~26.0ms  p99= ~2661ms  p99.9= ~2661ms
p99 amplification factor: ~85x
```

The baseline p99 is ~31ms. After fan-out, a 1% tail per downstream service compounds:
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
| `Post1Main.java` | Entry point — wires CLI args, runs both scenarios, writes output |

---

## Posts 2–6

Coming in Series 1. See [docs/TASKS.md](../docs/TASKS.md) for the full roadmap.

| Post | Topic |
|---|---|
| Post 2 | Queueing Theory for Engineers (Little's Law, saturation knee) |
| Post 3 | Hedged Requests & Speculative Execution |
| Post 4 | The Coordinated Omission Problem |
| Post 5 | Backpressure Design Patterns |
| Post 6 | SLO Engineering |
