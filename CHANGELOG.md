# Changelog

## [Unreleased] - 2026-06-07

### Added

**Series 3: Failure Propagation in Microservices - Post 2**

- Post 2 "Retry Storms and Amplification" (`runRetryStorms` + `runPost2` alias)
  - `retrystorm`: `RetryPolicy` (attempts per hop, per-call timeout, backoff; retry on any
    error - the naive policy the experiment indicts)
  - `retrystorm`: `RetryStormSimulator` - call-tree event loop where every downstream attempt
    is a node; timeout generations prevent a stale timeout from double-retrying after a fast
    response; abandoned callers keep retrying (abandonment gates only the upward notify) -
    the mechanism that compounds R attempts per hop into R^2 leaf attempts
  - `retrystorm`: `RetryStormScenario` - amplification sweep (R x healthy/hard-down;
    amplification measured over roots whose full retry tree fits the window: exactly 1/4/9,
    then 14.55 at R=4 where the storm saturates even a 200-worker middle tier) + transient
    1s degradation timeline (R=3 rescues clients at a 6x database attempt-rate spike where
    R=1 fails six windows)
  - `cascade`: additive `ServiceTime.degradedBetween` factory (transient degradation with
    recovery; Post 1 golden untouched)
  - `charting`: `StormChartGenerator` - storm attempt-rate, rescue, and R^2 amplification PNGs
  - Golden files in `golden/fp-post2/`; CI report step extended
  - Tests: hand-computed micro-cases (exact spawn counts and resolution times), stale-timeout
    invalidation, abandoned-keeps-retrying, rescue-at-amplified-cost timeline, golden
    regression, registry (2 experiments)

**Series 3: Failure Propagation in Microservices - Post 1**

- New `failure-propagation-lab` Gradle module (mirrors `backpressure-playground` plumbing:
  module-local `ExperimentRegistry` / `ExperimentDefinition` / `PostArtifacts`, golden dirs
  under `golden/fp-post{N}`)
- Post 1 "Cascading Failures Explained" (`runCascadingFailures` + `runPost1` alias)
  - `cascade`: `ServiceConfig` (bounded worker pool + unbounded FIFO queue), `ServiceTime`
    (constant / degradedAfter), `RouteDemand` (linear synchronous call chain)
  - `cascade`: `CascadeSimulator` - deterministic multi-service event loop; a synchronous call
    holds every upstream worker until the leaf completes (the cascade vector); no timeouts, no
    retries by design (Posts 2 and 4's subjects)
  - `cascade`: `CascadeScenario` - database service-time sweep across the 200ms capacity edge +
    mid-run degradation timeline (10ms -> 500ms at t=2s) with per-service queue-depth samples
  - `charting`: `CascadeChartGenerator` - sweep cliff and degradation-timeline PNGs
  - Golden files in `golden/fp-post1/`; CI generates and uploads a
    `failure-propagation-lab-reports-*` artifact per platform
  - Tests: cascade invariants (the failure crosses to a route that never touches the slow
    dependency; isolated frontend pools contain the blast; the backlog queues upstream of the
    bottleneck - frontend queue grows 0 -> 91 while the database queue reads 0), golden
    regression, registry
- Architecture per ADR-007: the golden contract for every Series 3 post is the synthetic
  simulation; Javalin live mode (real localhost HTTP over the same topology) lands in Post 3
  and is demonstrative, never golden-tested
- Root README Series 3 section

## [Unreleased] - 2026-06-06

### Added

**Series 2: Backpressure & Load Control - Post 5 (series complete)**

- Post 5 "Bounded Systems Architecture + SLO-Driven Load Control" in `backpressure-playground`
  - `slocontrol`: `ClassPolicy` - blind vs priority (a critical arrival evicts the newest
    queued background request when the bounded system is full)
  - `slocontrol`: `SloControlSimulator` - the assembled bounded system (Post 2 door bound +
    Post 4 dequeue expiry) serving two criticality classes; success-rate SLO scoring (served
    latency is deadline-flat by construction, so a latency SLO cannot tell the policies apart);
    fixed-seed pseudo-random class interleave (periodic patterns phase-lock with the service
    cadence); scoring window excludes arrivals without a full deadline left
  - `slocontrol`: `SloControlScenario` (protection sweep across the 400 rps ceiling + burst
    time series), `SloPointResult`, `SloWindowSample`, `SloRunResult`
  - `charting`: `SloControlChartGenerator` - burst hero chart and protection-ceiling sweep
  - `SloLoadControlMain` topic entry point; Gradle task `runSloLoadControl` (+ `runPost5` alias)
  - Golden CSV/PNG artifacts under `golden/bp-post5/`; capstone-invariant, golden, and registry tests
  - CI generates + uploads the Post 5 report alongside Posts 1-4

**Series 2: Backpressure & Load Control - Post 4**

- Post 4 "Load Shedding Strategies" in `backpressure-playground`
  - `shedding`: `ShedPolicy` - the four policies as pick order + door bound + dequeue expiry
    (fifo / tail-drop / expire / lifo)
  - `shedding`: `ShedSimulator` - deterministic event-loop model (dequeue policies break the
    closed-form forward pass); splits outcomes into goodput / served-late / shed, p99-of-served,
    and the shed-wait fast/slow/never spectrum; no post-window drain
  - `shedding`: `SheddingScenario` (offered-load sweep + burst-hangover time series),
    `ShedPointResult`, `ShedWindowSample`, `ShedRunResult`
  - `charting`: `SheddingChartGenerator` - burst-hangover and p99-of-served sweep PNGs (log axis)
  - `LoadSheddingMain` topic entry point; Gradle task `runLoadShedding` (+ `runPost4` alias)
  - Golden CSV/PNG artifacts under `golden/bp-post4/`; policy-invariant, golden, and registry tests
  - CI generates + uploads the Post 4 report alongside Posts 1-3

**Series 2: Backpressure & Load Control - Post 3**

- Post 3 "Token Bucket vs Leaky Bucket" in `backpressure-playground`
  - `shaping`: `RateGate` contract plus `TokenBucketGate` (policing: banked tokens, immediate
    release) and `LeakyBucketGate` (shaping: paced release, bounded gate queue)
  - `shaping`: `ShapingSimulator` - gate + the Posts 1-2 single-server FIFO; splits the p99 wait
    by where it happened (gate delay vs server wait) and tracks the peak downstream rate
  - `shaping`: `ShapingScenario` (burst-dimension sweep + downstream-rate time series),
    `ShapingPointResult`, `ShapingWindowSample`, `ShapingRunResult`
  - `charting`: `ShapingChartGenerator` - policing-vs-shaping time series and peak-downstream-rate
    sweep PNGs
  - `TokenVsLeakyMain` topic entry point; Gradle task `runTokenVsLeaky` (+ `runPost3` alias)
  - Golden CSV/PNG artifacts under `golden/bp-post3/`; gate-invariant, golden, and registry tests
  - CI generates + uploads the Post 3 report alongside Posts 1-2

### Changed

- `backpressure-playground/README.md`: Posts 3-5 sections with deterministic expected
  results; transferable-lessons list extended
- `README.md`: Series 2 table rows and run commands for Posts 3-5
- `.github/workflows/ci.yml`: Posts 3-5 report generation in the
  backpressure-playground report step
- `.idea/gradle.xml`: registered the `backpressure-playground` module

### Fixed

- `backpressure-playground` input guards: `SloControlSimulator` rejects run windows
  shorter than the client deadline (previously crashed in the burst series or silently
  inflated rates), `CollapseSimulator` rejects non-positive offered load (previously
  looped forever), and all five chart generators tolerate a parent-less `--output-dir`
  like the CSV writers already did
- `backpressure-playground/README.md`: Post 4 shed-wait p50 table cells corrected to the
  golden CSV values (fifo 1250, lifo 2495)

## [Unreleased] - 2026-06-03

### Added

**Series 2: Backpressure & Load Control - Post 2**

- Post 2 "Admission Control Design" in `backpressure-playground`
  - `admission`: `AdmissionSimulator` - single-server FIFO fronted by a concurrency limit
    (fail-fast reject); the antidote to Post 1's collapse
  - `admission`: `DemandCurve` (deterministic bursty/constant offered-load schedule),
    `AdmissionScenario` (limit sweep + offered-load plateau), `AdmissionPointResult`,
    `AdmissionRunResult`
  - `charting`: `AdmissionChartGenerator` - sweet-spot (goodput/utilization vs limit) and
    plateau-restored (goodput vs offered load) PNGs
  - `AdmissionControlMain` topic entry point; Gradle task `runAdmissionControl` (+ `runPost2` alias)
  - Golden CSV/PNG artifacts under `golden/bp-post2/`; simulator-invariant, golden, and registry tests
  - CI generates + uploads the Post 2 report alongside Post 1

**Series 2: Backpressure & Load Control - Post 1**

- `backpressure-playground` module with Post 1 "Why Systems Collapse Under Load"
  - `collapse`: `CollapseSimulator` - deterministic discrete-event model of an unmanaged
    single-server service; encodes service-then-discard (the server burns a slot on work whose
    client already gave up) and client-retry re-injection (the retry-storm death spiral)
  - `collapse`: `CollapseScenario` (offered-load sweep, with/without retries), `LoadLevelResult`,
    `CollapseRunResult`
  - `charting`: `CollapseChartGenerator` - goodput collapse cliff and retry-storm amplification PNGs
  - `LoadCollapseMain` topic entry point; Gradle task `runLoadCollapse` (+ `runPost1` alias)
  - Module-local experiment plumbing (`ExperimentRegistry`, `ExperimentDefinition`, `PostArtifacts`,
    `ArtifactWriteException`) keeping each lab self-contained
- Golden CSV/PNG reference artifacts under `golden/bp-post1/` (flat per-series naming)
- Golden CSV regression test, simulator-invariant tests, and registry tests for Series 2 Post 1
- Deterministic CI report artifact generation + upload for `backpressure-playground`

### Changed

- `lab-commons` `ExperimentManifest`: added a `write(...)` overload accepting an explicit golden
  directory so multiple series share the `golden/` root without post-number collisions; the
  existing `golden/post{N}` behaviour is unchanged
- `settings.gradle.kts`: included `backpressure-playground`
- `README.md`: added the Series 2 overview and repository-structure entry

## [Unreleased] - 2026-05-30

### Added

**Series 1: Tail Latency & System Behavior**

- `buildSrc/` convention plugin: Java 25 toolchain, `--enable-preview` on compile + test + run tasks, UTF-8 encoding, JUnit Platform
- `gradle/libs.versions.toml` version catalog: HdrHistogram 2.2.2, XChart 3.8.8, Resilience4j 2.2.0, JUnit 5.11.4
- `lab-commons` module implementing the shared runtime
  - `cli`: `CliParser` / `CliArgs` - parses the stable CLI flag contract (ADR-005)
  - `histogram`: `LatencyHistogram` (thread-safe Recorder-based HdrHistogram wrapper) + `PercentileSnapshot` record
  - `csv`: `CsvSnapshotWriter` and `CsvTableReader`
  - `terminal`: `TerminalRenderer` - TTY/streaming auto-detection with ANSI live table
  - `concurrency`: `ScopedRunner` - thin StructuredTaskScope wrapper (`fanOut`, `hedge`, `fanOutCollectAll`) + `WastedWorkCounter`
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
