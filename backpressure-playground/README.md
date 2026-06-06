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

## These are lab experiments, not production measurements

Every number in this module is produced by actually running the deterministic models here -
nothing is hand-written, curated, or extrapolated, and the golden-file tests re-run the models
and assert the output byte-for-byte. The data is real model output.

But the models are deliberately simple and **synthetic**, built so one mechanism is visible at a
time and so results are perfectly reproducible:

- a single server with a fixed service time (not a real multi-core service),
- deterministic arrivals - a fixed rate or a fixed bursty curve (not real stochastic traffic),
- a fixed client deadline; no network, no GC pauses, no downstream dependencies.

So treat the absolute figures (rps, ms, percentages, the exact sweet-spot limit) as artifacts of
these chosen constants, **not** as predictions for any real service. What transfers to production
is the **shape and the mechanism**, not the numbers:

- an unmanaged queue collapses goodput *below* capacity instead of plateauing at it (Post 1);
- retries amplify an overload without adding goodput (Post 1);
- a concurrency limit sized to roughly `capacity x deadline` restores the plateau (Post 2);
- token and leaky buckets bound the same average rate - the design choice is where the burst
  and the wait land: downstream of a policer, at the gate of a shaper (Post 3);
- every shedding policy restores goodput under overload - the design choice is who gets served
  and how long the doomed wait before they find out (Post 4).

In a real system the collapse point, the best limit, and the latencies will all differ with
traffic variance, service-time distribution, core count, and downstream behaviour - but the
failure modes and the fix are the same. `--deterministic` exists so the tests can assert
reproducibility; it is not a claim that production is deterministic.

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

---

## Post 3: Token Bucket vs Leaky Bucket

**TL;DR**
- Both gates bound the same average admitted rate, so goodput alone cannot tell them apart -
  the goodput columns in this experiment are near-identical at every burst size
- The design difference is *where the burst and the wait land*: a token bucket (policing) passes
  banked bursts downstream at line rate, so the wait queues up at the server; a leaky bucket
  (shaping) releases a flat stream at the leak rate, so the wait is held at the gate
- The burst knob (bucket size B / queue depth Q) shares Post 2's deadline budget:
  `capacity x deadline = 20`. Oversize either knob and the deadline is blown on that gate's own
  side - server wait for token, gate delay for leaky

Post 2 chose *how much* to admit; Post 3 chooses *how the admitted rate is delivered*.

### Run it

```bash
./gradlew :backpressure-playground:runTokenVsLeaky -Pargs="--deterministic --duration 5s --output-dir ./results/token-vs-leaky"
```

Like Posts 1-2, the model is a fully synthetic, single-threaded discrete-event simulation, so
output is byte-for-byte reproducible. `--concurrency` and `--snapshot-interval` are accepted for
CLI consistency but do not affect this experiment.

### What it models

The same fixed-capacity server (mu = 100 rps, 200ms deadline) fronted by a rate gate set to the
sustained rate of 100 rps. Two experiments:

1. **Sweeping the burst dimension** - a bursty demand curve (20 rps valleys for 1s, 600 rps
   spikes for 200ms, ~113 rps average) is run through each gate while its burst knob is swept
   from 1 to 80. The curve's long valley banks more burst credit than the largest swept bucket -
   under Post 2's shallower curve, sizes beyond the valley surplus would all behave alike.
2. **Shaping vs policing** - at the burst budget of 20, the downstream rate is sampled per 100ms
   window: the token bucket passes the spike through at up to 290 rps, the leaky bucket never
   exceeds 100 rps and smears the burst into the following valley.

### Expected golden output (deterministic, 5s)

Burst sweep (offered ~113 rps, capacity 100, deadline budget 20):

| Limiter | Burst | Goodput | Reject% | Served-late% | Gate-delay p99 | Server-wait p99 | Downstream peak |
|---|------:|--------:|--------:|-------------:|---------------:|----------------:|----------------:|
| token-bucket | 1 | 31.2 | 72.4 | 0.0 | 0 | 0 | 100 |
| token-bucket | **20** | **47.8** | 57.8 | 0.0 | 0 | 190 | 290 |
| token-bucket | 80 | 25.4 | 15.4 | 62.2 | 0 | 790 | 610 |
| leaky-bucket | 1 | 31.2 | 72.4 | 0.0 | 0 | 0 | 100 |
| leaky-bucket | **20** | **48.0** | 57.6 | 0.0 | 190 | 0 | 100 |
| leaky-bucket | 80 | 25.4 | 15.2 | 62.4 | 790 | 0 | 100 |

Read the columns in pairs: goodput is the same for both gates at every burst size, and the p99
wait mirrors exactly - 190ms at the server for token, 190ms at the gate for leaky, 790ms on
each gate's own side once the knob is 4x oversized. Only the downstream peak separates them:
the token bucket's grows with the bucket (100 -> 610 rps), the leaky bucket's never leaves the
leak rate. At burst dimension 1 the two gates converge to the same strict policer. Goodput tops
out near 48 rps, well below capacity: a rate gate cannot bank idle *server* time across valleys
the way Post 2's concurrency limit can - rejecting at the gate by rate, not by what the server
could still absorb, is the price of bounding the downstream rate.

### Output files

| File | Contents |
|---|---|
| `bp-post3-burst-sweep.csv` | One row per (limiter, burst dimension) over the bursty curve - golden contract |
| `bp-post3-shaping.csv` | Downstream rate per 100ms window: offered vs token vs leaky - golden contract |
| `bp-post3-shaping.png` | Policing passes the burst, shaping flattens it |
| `bp-post3-burst-sweep.png` | Peak downstream rate vs burst dimension - the burst the server sees |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `shaping/RateGate.java` | Gate contract: reject, or admit with a downstream release time |
| `shaping/TokenBucketGate.java` | Policing: banked tokens, immediate release |
| `shaping/LeakyBucketGate.java` | Shaping: paced release at the leak rate, bounded gate queue |
| `shaping/ShapingSimulator.java` | Gate + single-server FIFO; splits the wait by where it happened |
| `shaping/ShapingScenario.java` | The burst sweep and the downstream-rate time series |
| `charting/ShapingChartGenerator.java` | XChart PNGs for the shaping and burst-sweep charts |
| `TokenVsLeakyMain.java` | Entry point: CSVs, charts, manifest, report |

---

## Post 4: Load Shedding Strategies

**TL;DR**
- Under sustained overload, every policy that sheds restores goodput to capacity - the choice is
  *who gets served* and *how long the doomed wait*
- The fingerprints: `tail-drop` fast-fails at the door (shed wait 0ms) but needs its bound sized
  to `capacity x deadline`; `expire` discards doomed work free at dequeue (no knob, but the shed
  wait the client endures is the full deadline); `lifo` serves the freshest through any backlog
  (p99 of served stays ~10ms) but never tells the starved
- The burst hangover is the killer: without shedding, FIFO keeps serving stale backlog long
  after the spike ended - the system stays slow when the load is already gone

Posts 2-3 decided how much to admit and how to deliver it; Post 4 decides which work to abandon.

### Run it

```bash
./gradlew :backpressure-playground:runLoadShedding -Pargs="--deterministic --duration 5s --output-dir ./results/load-shedding"
```

Like the other Series 2 posts, the model is a fully synthetic, single-threaded discrete-event
simulation, so output is byte-for-byte reproducible. `--concurrency` and `--snapshot-interval`
are accepted for CLI consistency but do not affect this experiment.

### What it models

The same fixed-capacity server (mu = 100 rps, 200ms deadline) behind an explicit queue, with
four dequeue/door policies: `fifo` (unbounded, no shedding - the Post 1 baseline), `tail-drop`
(FIFO with in-system occupancy capped at the deadline budget of 20), `expire` (FIFO, but work
that can no longer make its deadline is discarded free at dequeue), and `lifo` (newest-first;
the old are shed by starvation). No post-window drain: whatever is still queued when the run
window closes counts as shed, so the shares always sum over the same arrivals.

1. **The sweep** - offered load swept over constant levels per policy.
2. **The hangover (the hero)** - a repeating burst curve (80 rps valleys, 600 rps spikes for
   500ms); the p99 of completed work is sampled per 100ms window.

### Expected golden output (deterministic, 5s)

Sweep at 2x capacity (200 rps offered):

| Policy | Goodput | Shed% | Served-late% | p99 served | Shed wait p50 | Wasted% |
|---|--------:|------:|-------------:|-----------:|--------------:|--------:|
| fifo | 7.8 | 50.0 | 46.1 | 2481 | 2475 | 92.2 |
| tail-drop | 100.0 | 50.0 | 0.0 | 200 | **0** | 0.0 |
| expire | 100.0 | 50.0 | 0.0 | 200 | **195** | 0.0 |
| lifo | 100.0 | 50.0 | 0.0 | **10** | **2497** | 0.0 |

Goodput and shed% are identical for the three real policies - they cannot be ranked on how much
they shed, only on who they serve and how the shed find out. `tail-drop` and `expire` serve the
same near-deadline work (their hangover lines coincide); they differ in where the shed cost
lands: tail-drop rejects in 0ms at the door but needs its bound sized to `capacity x deadline`,
expire needs no knob (the deadline is the knob) but holds doomed requests ~the full deadline
before discarding. `lifo` serves fresh ~10ms work through any backlog and the starved are never
told (their shed wait is the rest of the window). In the hangover chart, `fifo`'s p99 climbs
monotonically across spikes (the backlog compounds - it never recovers inside the run), while
`lifo` inverts: fresh during the spike, then serving bottom-of-stack zombies during the valleys
- pure LIFO needs an expiry check in practice.

### Output files

| File | Contents |
|---|---|
| `bp-post4-shed-sweep.csv` | One row per (policy, offered load) - golden contract |
| `bp-post4-hangover.csv` | p99-of-served per 100ms window per policy - golden contract |
| `bp-post4-hangover.png` | The burst hangover over time (log axis) |
| `bp-post4-shed-sweep.png` | p99-of-served vs offered load per policy (log axis) |
| `manifest.json` / `report.html` | Run receipt and self-contained HTML report |

### Key source files

| File | Role |
|---|---|
| `shedding/ShedPolicy.java` | The four policies: pick order + door bound + dequeue expiry |
| `shedding/ShedSimulator.java` | Event-loop model (dequeue policy breaks the closed-form pass) |
| `shedding/SheddingScenario.java` | The offered-load sweep and the burst-hangover time series |
| `charting/SheddingChartGenerator.java` | XChart PNGs for the hangover and sweep charts |
| `LoadSheddingMain.java` | Entry point: CSVs, charts, manifest, report |
