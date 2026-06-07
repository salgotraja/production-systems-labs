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
package dev.engnotes.labs.backpressure.slocontrol;

import java.util.List;

/**
 * Full result of the SLO-driven load-control experiment.
 *
 * @param serverCapacityRps    fixed server capacity mu in rps
 * @param queueBound           the door bound: {@code capacity x deadline} (Post 2's number)
 * @param criticalSharePct     the critical class's share of offered traffic
 * @param protectionCeilingRps total offered load beyond which even priority cannot hold the SLO
 * @param sloTargetPct         the success-rate SLO for the critical class
 * @param sweep                experiment 1: offered sweep per policy (policy-major order)
 * @param windows              experiment 2: per-window class success rates over the burst curve
 */
public record SloRunResult(
        double serverCapacityRps,
        int queueBound,
        double criticalSharePct,
        double protectionCeilingRps,
        double sloTargetPct,
        List<SloPointResult> sweep,
        List<SloWindowSample> windows) {

    public SloRunResult {
        sweep = List.copyOf(sweep);
        windows = List.copyOf(windows);
    }
}
