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

import java.util.List;

/**
 * Full result of the token-bucket vs leaky-bucket experiment.
 *
 * @param serverCapacityRps fixed server capacity mu in rps
 * @param gateRateRps       sustained rate both gates are set to (= capacity)
 * @param burstSweetSpot    deadline-derived burst budget, {@code capacity x deadline}
 * @param tokenSweep        experiment 1: sweep bucket size B over the bursty curve
 * @param leakySweep        experiment 1: sweep queue depth Q over the bursty curve
 * @param windows           experiment 2: downstream rate per 100ms window at the sweet spot
 */
public record ShapingRunResult(
        double serverCapacityRps,
        double gateRateRps,
        int burstSweetSpot,
        List<ShapingPointResult> tokenSweep,
        List<ShapingPointResult> leakySweep,
        List<ShapingWindowSample> windows) {

    public ShapingRunResult {
        tokenSweep = List.copyOf(tokenSweep);
        leakySweep = List.copyOf(leakySweep);
        windows = List.copyOf(windows);
    }
}
