/*
 * Copyright 2026 engnotes.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.engnotes.labs.backpressure.shaping;

/**
 * Outcome of one rate-gated run (one limiter at one burst capacity).
 *
 * <p>The two p99 columns split the same end-to-end wait by <em>where it happened</em> - that
 * split, not goodput, is what tells the two limiters apart.
 *
 * @param limiter           gate discipline label ({@code token-bucket} or {@code leaky-bucket})
 * @param burstCapacity     bucket size B (token) or queue depth Q (leaky) - the swept knob
 * @param offeredRps        average offered load over the run
 * @param goodputRps        requests completed within the client deadline, per second
 * @param rejectPct         percentage of arrivals turned away at the gate (fail-fast)
 * @param servedLatePct     percentage of arrivals admitted, served, but past the deadline (wasted)
 * @param gateDelayP99Ms    99th-percentile wait at the gate (release - arrival); the leaky bucket's cost
 * @param serverWaitP99Ms   99th-percentile queueing wait at the server (start - release); the token bucket's cost
 * @param downstreamPeakRps peak downstream rate over any 100ms window - the burst the server actually sees
 */
public record ShapingPointResult(
        String limiter,
        int burstCapacity,
        double offeredRps,
        double goodputRps,
        double rejectPct,
        double servedLatePct,
        double gateDelayP99Ms,
        double serverWaitP99Ms,
        double downstreamPeakRps) {
}
