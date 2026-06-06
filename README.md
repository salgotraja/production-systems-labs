# Production Systems Labs

Companion research harness for the **Production Systems Engineering** blog series on [engnotes.dev](https://engnotes.dev).

Runnable Java experiments - tail latency, queueing theory, hedged requests, coordinated omission, backpressure, SLO engineering.
Experiments use topic-based class and task names; article numbers are compatibility aliases.

## Prerequisites

- Java 25 (Temurin recommended)
- Gradle wrapper included

## Quick Start

```bash
# Clone and verify the build
git clone https://github.com/salgotraja/production-systems-labs.git
cd production-systems-labs
./gradlew build --no-daemon
```

## Series 1 - Tail Latency & System Behavior (`latency-lab`)

| Post | Topic                                   | Gradle Task | Deterministic result |
|------|-----------------------------------------|-------------|----------------------|
| 1 | [Why Average Latency Lies](https://engnotes.dev/blog/tail-latency-system-behavior/part-1-why-average-latency-lies)           | `./gradlew :latency-lab:runTailLatency` | baseline p99 `34.0ms`, fan-out p99 `597.0ms` |
| 2 | Queueing Theory for Engineers           | `./gradlew :latency-lab:runQueueSaturation` | rho `1.30` p99 `605.0ms` |
| 3 | Hedged Requests & Speculative Execution | `./gradlew :latency-lab:runHedgedRequests` | p95 hedge p99 `43.0ms`, extra load `3.7%` |
| 4 | The Coordinated Omission Problem        | `./gradlew :latency-lab:runCoordinatedOmission` | closed-loop raw p99 `10.0ms`, corrected p99 `460.0ms` |
| 5 | Backpressure Design Patterns            | `./gradlew :latency-lab:runBackpressure` | token-bucket accepted `599`, rejected `401`, p99 `10.0ms` |
| 6 | SLO Engineering                         | `./gradlew :latency-lab:runSloPolicy` | bulkhead SLI `99.60%`, worst burn `1.00x` |

### Standard Flags (all experiments)

```bash
./gradlew :latency-lab:runTailLatency -Pargs="--deterministic --duration 5s --concurrency 10 --output-dir ./results/tail-latency --snapshot-interval 1s"
```

| Flag | Default | Description |
|------|---------|-------------|
| `--deterministic` | false | Fixed seed - reproducible output matching golden files |
| `--duration` | 30s | Experiment run time |
| `--concurrency` | 100 | Concurrent virtual clients |
| `--output-dir` | ./results | CSV and PNG output directory |
| `--snapshot-interval` | 1s | Time between CSV rows |

Every run writes `manifest.json` and `report.html` next to the CSV/PNG outputs. The report is self-contained HTML: it embeds the manifest and CSV data, opens directly from `file://`, has no server or external JavaScript dependency, and exposes truth controls for measurement mode, hedging state, and metric view when the data supports them.

## Series 2 - Backpressure & Load Control (`backpressure-playground`)

| Post | Topic | Gradle Task | Deterministic result |
|------|-------|-------------|----------------------|
| 1 | Why Systems Collapse Under Load | `./gradlew :backpressure-playground:runLoadCollapse` | capacity `100` rps; goodput collapses to `7.8` rps at 200 rps offered; retries push effective load to `716.6` rps |
| 2 | Admission Control Design | `./gradlew :backpressure-playground:runAdmissionControl` | sweet spot at limit `20` (Little's Law) goodput `99.8` rps; admission control holds `~100` rps where no control collapses to `7.8` |
| 3 | Token Bucket vs Leaky Bucket | `./gradlew :backpressure-playground:runTokenVsLeaky` | same goodput from both gates at every burst size; at budget `20` the p99 wait is `190` ms at the server (token) vs `190` ms at the gate (leaky); downstream peak `290` vs `100` rps |
| 4 | Load Shedding Strategies | `./gradlew :backpressure-playground:runLoadShedding` | at 2x capacity all shedding policies hold goodput `100` rps vs fifo's `7.8`; the fingerprints: tail-drop sheds in `0` ms, expire in `~195` ms, lifo never tells (p99-of-served `200` / `200` / `10` ms) |

```bash
./gradlew :backpressure-playground:runLoadCollapse -Pargs="--deterministic --duration 5s --output-dir ./results/load-collapse"
./gradlew :backpressure-playground:runAdmissionControl -Pargs="--deterministic --duration 5s --output-dir ./results/admission-control"
./gradlew :backpressure-playground:runTokenVsLeaky -Pargs="--deterministic --duration 5s --output-dir ./results/token-vs-leaky"
./gradlew :backpressure-playground:runLoadShedding -Pargs="--deterministic --duration 5s --output-dir ./results/load-shedding"
```

Series 2 golden files live under `golden/bp-post{N}/`. Series 2 posts emit their own sweep CSV
schemas (one row per swept level) rather than the per-second ADR-005 schema, because they sweep
offered-load, admission-limit, or burst-dimension levels instead of time.

These are deterministic **synthetic lab experiments**: the numbers are real model output (golden-
tested), but the models are intentionally simple (single server, fixed service time, deterministic
arrivals). The absolute figures are artifacts of the chosen constants; what transfers to production
is the mechanism, not the numbers. See the [module README](backpressure-playground/README.md) for
the full caveat.

## Repository Structure

```
production-systems-labs/
├── build.gradle.kts          # root: group/version only
├── settings.gradle.kts       # includes lab-commons + latency-lab + backpressure-playground
├── gradle/libs.versions.toml # pinned dependency versions
├── buildSrc/                 # shared Java 25 convention plugin
├── lab-commons/              # shared: histogram, csv, cli, terminal, concurrency
├── latency-lab/              # Series 1 (6 posts)
├── backpressure-playground/  # Series 2 (Posts 1-4)
├── golden/                   # reference output for golden file tests
└── .github/workflows/        # build + golden CSV regression tests
```

## CSV Output Contract

All experiments write CSV snapshots with this fixed schema (ADR-005):

```
timestamp_ms,elapsed_s,p50_ms,p95_ms,p99_ms,p999_ms,throughput_rps,error_count,total_requests
```

Columns are append-only - new columns may be added to the right in future posts.

The checked-in golden CSV files are the numeric contract. PNG files and generated HTML reports are visual references; CI does not diff images or HTML.

## License

Apache 2.0 - see [LICENSE](LICENSE).
