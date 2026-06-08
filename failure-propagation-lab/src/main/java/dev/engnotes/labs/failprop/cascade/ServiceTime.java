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
package dev.engnotes.labs.failprop.cascade;

/**
 * A service's own-work duration as a function of simulated time, evaluated when the work
 * starts. Deterministic by construction: the same start time always yields the same duration,
 * which is what lets a mid-run degradation ship a golden file.
 */
@FunctionalInterface
public interface ServiceTime {

    /** Own-work duration in milliseconds for work starting at {@code startMs}. */
    long at(double startMs);

    /** A fixed service time, the sweep building block. */
    static ServiceTime constant(long ms) {
        if (ms <= 0) {
            throw new IllegalArgumentException("service time must be > 0");
        }
        return startMs -> ms;
    }

    /**
     * A step degradation: {@code healthyMs} before {@code thresholdMs}, {@code degradedMs}
     * from then on. Models a dependency going slow mid-run without ever recovering - the
     * timeline experiment's shape.
     */
    static ServiceTime degradedAfter(double thresholdMs, long healthyMs, long degradedMs) {
        if (healthyMs <= 0 || degradedMs <= 0) {
            throw new IllegalArgumentException("service times must be > 0");
        }
        return startMs -> startMs < thresholdMs ? healthyMs : degradedMs;
    }

    /**
     * A transient degradation: {@code degradedMs} inside {@code [fromMs, toMs)}, healthy
     * outside it. Models a dependency that goes slow and then <em>recovers</em> - what reveals
     * whether the load the failure generated outlives the failure itself.
     */
    static ServiceTime degradedBetween(double fromMs, double toMs, long healthyMs, long degradedMs) {
        if (healthyMs <= 0 || degradedMs <= 0) {
            throw new IllegalArgumentException("service times must be > 0");
        }
        if (fromMs >= toMs) {
            throw new IllegalArgumentException("fromMs must be < toMs");
        }
        return startMs -> startMs >= fromMs && startMs < toMs ? degradedMs : healthyMs;
    }

    /**
     * A <em>partial</em> degradation inside {@code [fromMs, toMs)}: a fixed fraction of calls
     * are slow, the rest healthy; outside the window every call is healthy. The slow/fast
     * decision is a fixed-seed pseudo-random draw per call (not a periodic every-Nth pattern,
     * which phase-locks with the service cadence - the Series 2 Post 5 lesson), so it is
     * deterministic and golden-stable while staying uncorrelated with arrival timing.
     *
     * <p>This is the scenario a circuit breaker cannot help with: if {@code slowFraction} stays
     * below the breaker's trip threshold, the breaker never opens, yet every slow call still
     * spawns retry work that a propagated deadline is the only thing left to bound. The returned
     * instance is stateful (it counts calls) and therefore single-use per simulation run, which
     * the lab's fresh-per-run topology construction already guarantees.
     */
    static ServiceTime partialDegradation(
            double fromMs, double toMs, long healthyMs, long degradedMs, double slowFraction, long seed) {
        if (healthyMs <= 0 || degradedMs <= 0) {
            throw new IllegalArgumentException("service times must be > 0");
        }
        if (fromMs >= toMs) {
            throw new IllegalArgumentException("fromMs must be < toMs");
        }
        if (slowFraction < 0.0 || slowFraction > 1.0) {
            throw new IllegalArgumentException("slowFraction must be in [0, 1]");
        }
        java.util.Random random = new java.util.Random(seed);
        return startMs -> {
            if (startMs < fromMs || startMs >= toMs) {
                return healthyMs;
            }
            return random.nextDouble() < slowFraction ? degradedMs : healthyMs;
        };
    }
}
