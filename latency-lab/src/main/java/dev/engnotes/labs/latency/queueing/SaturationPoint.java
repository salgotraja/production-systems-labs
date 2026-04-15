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
 * Metrics collected at one utilization level in a saturation sweep.
 *
 * @param targetUtilization   ρ = arrivalRate / serviceRate (e.g. 0.8 = 80% utilization)
 * @param targetRps           arrival rate submitted to the simulator
 * @param actualThroughputRps requests completed per second (≤ targetRps under saturation)
 * @param p50Ms               median sojourn time in milliseconds
 * @param p99Ms               99th-percentile sojourn time in milliseconds
 * @param p999Ms              99.9th-percentile sojourn time in milliseconds
 * @param meanSojournMs       mean sojourn time used for Little's Law
 * @param avgQueueDepth       time-averaged queue depth
 * @param rejectionCount      requests rejected because the queue was full
 * @param littlesLaw          Little's Law verification result
 */
public record SaturationPoint(
        double targetUtilization,
        double targetRps,
        double actualThroughputRps,
        double p50Ms,
        double p99Ms,
        double p999Ms,
        double meanSojournMs,
        double avgQueueDepth,
        long rejectionCount,
        LittlesLawResult littlesLaw) {}
