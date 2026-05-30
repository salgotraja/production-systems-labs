# Changelog

## [Unreleased] — 2026-05-30

### Added

**Series 1: Tail Latency & System Behavior**

- `buildSrc/` convention plugin: Java 25 toolchain, `--enable-preview` on compile + test + run tasks, UTF-8 encoding, JUnit Platform
- `gradle/libs.versions.toml` version catalog: HdrHistogram 2.2.2, XChart 3.8.8, Resilience4j 2.2.0, JUnit 5.11.4
- `lab-commons` module implementing the shared runtime
  - `cli`: `CliParser` / `CliArgs` — parses the stable CLI flag contract (ADR-005)
  - `histogram`: `LatencyHistogram` (thread-safe Recorder-based HdrHistogram wrapper) + `PercentileSnapshot` record
  - `csv`: `CsvSnapshotWriter` and `CsvTableReader`
  - `terminal`: `TerminalRenderer` — TTY/streaming auto-detection with ANSI live table
  - `concurrency`: `ScopedRunner` — thin StructuredTaskScope wrapper (`fanOut`, `hedge`, `fanOutCollectAll`) + `WastedWorkCounter`
  - `manifest`: per-run `manifest.json` writer
  - `report`: self-contained `report.html` writer with embedded CSV/manifest data and truth controls
- `latency-lab` implements six experiments:
  - tail latency and fan-out amplification
  - queue saturation and Little's Law
  - hedged requests and wasted-work cost model
  - coordinated omission measurement
  - backpressure strategy comparison
  - SLO policy and burn-rate simulation
- Topic-based Gradle tasks: `runTailLatency`, `runQueueSaturation`, `runHedgedRequests`, `runCoordinatedOmission`, `runBackpressure`, and `runSloPolicy`
- Compatibility aliases: `runPost1` through `runPost6`
- Golden CSV/PNG reference artifacts for all six experiments under `golden/post{1-6}/`
- Golden CSV regression tests for all six deterministic scenarios
- Deterministic CI report artifact generation for all six experiments
- Ubuntu/macOS/Windows GitHub Actions matrix
- `docs/CHEATSHEET.md`, `docs/RELIABILITY_MAPS.md`, `docs/LAUNCH_CHECKLIST.md`, and `docs/nfr5-verification.txt`
- `docs/adr/006-topic-based-experiment-entrypoints.md`
- `LICENSE` (Apache 2.0)
- `README.md` with quick start, series overview, and CLI contract table

### Changed

- Root `build.gradle.kts`: stripped to group/version only (no java plugin at root)
- `settings.gradle.kts`: added `dependencyResolutionManagement` with mavenCentral and the Foojay toolchain resolver
- `lab-commons/build.gradle.kts`: rewrote to use convention plugin and version catalog
- `.gitignore`: added `results/` and `ci-results/` exclusions
- Launch-facing docs now use topic-based experiment tasks instead of publication-order names
