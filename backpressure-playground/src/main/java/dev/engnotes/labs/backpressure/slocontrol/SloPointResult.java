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

/**
 * Outcome of one SLO-control run (one policy at one offered load), split by criticality class.
 *
 * <p>Total goodput is collinear between the policies (~capacity under any overload) and the
 * served p99s are deadline-flat by construction - the discriminating column is
 * {@code criticalSuccessPct}: blind shedding degrades it in lockstep with background, priority
 * holds it at ~100 until the protection ceiling.
 *
 * @param policy               class policy label ({@code blind} or {@code priority})
 * @param offeredRps           total average offered load over the run
 * @param criticalOfferedRps   the critical class's share of the offered load
 * @param criticalSuccessPct   critical arrivals completed in deadline / critical arrivals
 * @param backgroundSuccessPct background arrivals completed in deadline / background arrivals
 * @param criticalP99Ms        p99 sojourn of served critical requests (deadline-flat by design)
 * @param backgroundP99Ms      p99 sojourn of served background requests (deadline-flat by design)
 * @param criticalSloMet       whether critical success met the {@link SloControlSimulator#SLO_TARGET_PCT} SLO
 * @param totalGoodputRps      all requests completed in deadline, per second (collinear)
 */
public record SloPointResult(
        String policy,
        double offeredRps,
        double criticalOfferedRps,
        double criticalSuccessPct,
        double backgroundSuccessPct,
        double criticalP99Ms,
        double backgroundP99Ms,
        boolean criticalSloMet,
        double totalGoodputRps) {
}
