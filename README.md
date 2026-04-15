# Production Systems Labs

Companion research harness for the **Production Systems Engineering** blog series on [engnotes.dev](https://engnotes.dev).

Runnable Java experiments — tail latency, queueing theory, hedged requests, coordinated omission, backpressure, SLO engineering.

## Prerequisites

- Java 25 (Temurin recommended)
- Gradle 8.10+ (wrapper included — no separate install needed)

## Quick Start

```bash
# Clone and verify the build
git clone https://github.com/jagdish-engnotes/production-systems-labs.git
cd production-systems-labs
./gradlew :lab-commons:build
```

## Series 1 — Tail Latency & System Behavior (`latency-lab`)

| Post | Topic | Gradle Task |
|------|-------|-------------|
| 1 | Why Average Latency Lies | `./gradlew :latency-lab:runPost1` |
| 2 | Queueing Theory for Engineers | `./gradlew :latency-lab:runPost2` |
| 3 | Hedged Requests & Speculative Execution | `./gradlew :latency-lab:runPost3` |
| 4 | The Coordinated Omission Problem | `./gradlew :latency-lab:runPost4` |
| 5 | Backpressure Design Patterns | `./gradlew :latency-lab:runPost5` |
| 6 | SLO Engineering | `./gradlew :latency-lab:runPost6` |

### Standard Flags (all experiments)

```bash
./gradlew :latency-lab:runPost1 --args="--deterministic --duration 30s --concurrency 100 --output-dir ./results"
```

| Flag | Default | Description |
|------|---------|-------------|
| `--deterministic` | false | Fixed seed — reproducible output matching golden files |
| `--duration` | 30s | Experiment run time |
| `--concurrency` | 100 | Concurrent virtual clients |
| `--output-dir` | ./results | CSV and PNG output directory |
| `--snapshot-interval` | 1s | Time between CSV rows |

## Repository Structure

```
production-systems-labs/
├── build.gradle.kts          # root: group/version only
├── settings.gradle.kts       # includes lab-commons + latency-lab
├── gradle/libs.versions.toml # pinned dependency versions
├── buildSrc/                 # shared Java 25 convention plugin
├── lab-commons/              # shared: histogram, csv, cli, terminal, concurrency
├── latency-lab/              # Series 1 (6 posts)
├── golden/                   # reference output for golden file tests
├── docs/                     # requirements, tasks, ADRs
└── .github/workflows/        # selective CI builds
```

## CSV Output Contract

All experiments write CSV snapshots with this fixed schema (ADR-005):

```
timestamp_ms,elapsed_s,p50_ms,p95_ms,p99_ms,p999_ms,throughput_rps,error_count,total_requests
```

Columns are append-only — new columns may be added to the right in future posts.

## License

Apache 2.0 — see [LICENSE](LICENSE).
