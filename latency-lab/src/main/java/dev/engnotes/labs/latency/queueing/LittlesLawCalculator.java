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
package dev.engnotes.labs.latency.queueing;

/**
 * Computes and validates Little's Law from measured queueing data.
 * All methods are static — no instances needed.
 */
public final class LittlesLawCalculator {

    private static final double MILLIS_PER_SECOND = 1000.0;

    private LittlesLawCalculator() {}

    /**
     * Validates Little's Law: L = λW.
     *
     * @param actualThroughputRps  measured throughput (λ), requests per second
     * @param meanSojournMs        measured mean sojourn time (W), in milliseconds
     * @param avgQueueDepth        measured time-averaged queue depth (L)
     * @return Little's Law result with computed vs measured L and relative error
     */
    public static LittlesLawResult compute(
            double actualThroughputRps,
            double meanSojournMs,
            double avgQueueDepth) {

        double meanSojournSecs = meanSojournMs / MILLIS_PER_SECOND;
        double computedL = actualThroughputRps * meanSojournSecs;
        double relativeError = avgQueueDepth > 0.0
                ? Math.abs(computedL - avgQueueDepth) / avgQueueDepth
                : 0.0;
        return new LittlesLawResult(actualThroughputRps, meanSojournMs,
                computedL, avgQueueDepth, relativeError);
    }
}
