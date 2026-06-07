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
package dev.engnotes.labs.backpressure.shedding;

import java.util.List;

/**
 * Full result of the load-shedding experiment.
 *
 * @param serverCapacityRps fixed server capacity mu in rps
 * @param queueBound        tail-drop's knob: {@code capacity x deadline} (Post 2's number)
 * @param sweep             experiment 1: offered-load sweep per policy (policy-major order)
 * @param hangover          experiment 2: per-window p99-of-served per policy over the burst curve
 */
public record ShedRunResult(
        double serverCapacityRps,
        int queueBound,
        List<ShedPointResult> sweep,
        List<ShedWindowSample> hangover) {

    public ShedRunResult {
        sweep = List.copyOf(sweep);
        hangover = List.copyOf(hangover);
    }
}
