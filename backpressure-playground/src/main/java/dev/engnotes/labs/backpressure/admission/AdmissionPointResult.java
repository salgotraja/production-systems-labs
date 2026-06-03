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

/**
 * Outcome of one admission-control run (one limit at one offered load).
 *
 * @param admissionLimit  max in-flight requests admitted; {@link AdmissionSimulator#NO_LIMIT} means none
 * @param offeredRps      average offered load over the run
 * @param goodputRps      requests completed within the client deadline, per second
 * @param rejectPct       percentage of arrivals turned away at the door (fail-fast)
 * @param servedLatePct   percentage of arrivals admitted, served, but past the deadline (wasted)
 * @param p99Ms           99th-percentile sojourn of admitted-and-served requests
 * @param utilizationPct  fraction of the window the single server was busy
 */
public record AdmissionPointResult(
        int admissionLimit,
        double offeredRps,
        double goodputRps,
        double rejectPct,
        double servedLatePct,
        double p99Ms,
        double utilizationPct) {
}
