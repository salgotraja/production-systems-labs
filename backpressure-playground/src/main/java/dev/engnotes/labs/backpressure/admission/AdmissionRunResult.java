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
package dev.engnotes.labs.backpressure.admission;

import java.util.List;

/**
 * Full result of the admission-control experiment.
 *
 * @param serverCapacityRps fixed server capacity mu in rps
 * @param littlesLawLimit   capacity x deadline - the predicted sweet-spot admission limit
 * @param limitSweep        experiment 1: vary the admission limit over a fixed bursty demand curve
 * @param offeredNoControl  experiment 2: vary offered load with no admission control (the cliff)
 * @param offeredLimited    experiment 2: vary offered load at the Little's-Law limit (the plateau)
 */
public record AdmissionRunResult(
        double serverCapacityRps,
        int littlesLawLimit,
        List<AdmissionPointResult> limitSweep,
        List<AdmissionPointResult> offeredNoControl,
        List<AdmissionPointResult> offeredLimited) {

    public AdmissionRunResult {
        limitSweep = List.copyOf(limitSweep);
        offeredNoControl = List.copyOf(offeredNoControl);
        offeredLimited = List.copyOf(offeredLimited);
    }
}
