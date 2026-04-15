# Changelog

## [Unreleased] — 2026-04-14

### Added

**Phase 0: Mono-Repo Foundation**

- `buildSrc/` convention plugin: Java 25 toolchain, `--enable-preview` on compile + test + run tasks, UTF-8 encoding, JUnit Platform
- `gradle/libs.versions.toml` version catalog: HdrHistogram 2.2.2, XChart 3.8.8, Resilience4j 2.2.0, JUnit 5.11.4
- `lab-commons` module: five packages implementing the shared runtime
  - `cli`: `CliParser` / `CliArgs` — parses the stable CLI flag contract (ADR-005)
  - `histogram`: `LatencyHistogram` (thread-safe Recorder-based HdrHistogram wrapper) + `PercentileSnapshot` record
  - `csv`: `CsvSnapshotWriter` — writes the ADR-005 CSV contract
  - `terminal`: `TerminalRenderer` — TTY/streaming auto-detection with ANSI live table
  - `concurrency`: `ScopedRunner` — thin StructuredTaskScope wrapper (`fanOut`, `hedge`, `fanOutCollectAll`) + `WastedWorkCounter`
- `LICENSE` (Apache 2.0)
- `README.md` with quick start, series overview, and CLI contract table
- `golden/post{1-6}/` placeholder directories for reference outputs
- `.github/workflows/ci.yml` — selective builds per lab with golden file regression check on main

### Changed

- Root `build.gradle.kts`: stripped to group/version only (no java plugin at root)
- `settings.gradle.kts`: added `dependencyResolutionManagement` with mavenCentral
- `lab-commons/build.gradle.kts`: rewrote to use convention plugin and version catalog
- `.gitignore`: added `results/` and `ci-results/` exclusions
