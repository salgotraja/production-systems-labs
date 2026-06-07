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
package dev.engnotes.labs.failprop.breaker;

/**
 * Circuit breaker tuning. The window is count-based (the last {@code slidingWindowSize} call
 * outcomes), which keeps the breaker fully deterministic under synthetic time: no bucket
 * boundaries, no wall-clock coupling.
 *
 * @param failureRateThresholdPct trip when the windowed failure rate reaches this (and at
 *                                least {@code minimumCalls} outcomes are recorded)
 * @param slidingWindowSize       how many recent call outcomes the failure rate is computed over
 * @param minimumCalls            outcomes required before the rate is meaningful enough to trip
 * @param openDurationMs          how long to fail fast before probing
 * @param halfOpenProbes          trial calls permitted in half-open; all must succeed to close
 */
public record BreakerConfig(
        double failureRateThresholdPct,
        int slidingWindowSize,
        int minimumCalls,
        long openDurationMs,
        int halfOpenProbes) {

    public BreakerConfig {
        if (failureRateThresholdPct <= 0.0 || failureRateThresholdPct > 100.0) {
            throw new IllegalArgumentException("failureRateThresholdPct must be in (0, 100]");
        }
        if (slidingWindowSize < 1) {
            throw new IllegalArgumentException("slidingWindowSize must be >= 1");
        }
        if (minimumCalls < 1 || minimumCalls > slidingWindowSize) {
            throw new IllegalArgumentException("minimumCalls must be in [1, slidingWindowSize]");
        }
        if (openDurationMs <= 0) {
            throw new IllegalArgumentException("openDurationMs must be > 0");
        }
        if (halfOpenProbes < 1) {
            throw new IllegalArgumentException("halfOpenProbes must be >= 1");
        }
    }
}
