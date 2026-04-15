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

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a saturation sweep: executes QueueSimulator at 12 utilization levels from
 * ρ=0.1 to ρ=1.2 and returns a SaturationPoint per level.
 *
 * <p>The sweep reveals:
 * <ul>
 *   <li>The "knee of the curve" where queue depth and latency begin exponential growth (~ρ=0.7)</li>
 *   <li>Throughput collapse and rejection storms above ρ=1.0</li>
 *   <li>Little's Law holds across all utilization levels (measured L ≈ computed λW)</li>
 * </ul>
 */
public final class SaturationScenario {

    // 12 utilization levels from under-loaded to over-saturated
    private static final double[] UTILIZATION_LEVELS = {
        0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2
    };

    private static final int QUEUE_CAPACITY = 200;
    private static final int WORKER_COUNT = 1;

    private final long serviceTimeMs;
    private final boolean deterministic;

    /**
     * Creates a saturation scenario with the given service time and determinism setting.
     *
     * @param serviceTimeMs average time (in milliseconds) to service one request
     * @param deterministic if true, uses fixed-seed delays for reproducible results
     */
    public SaturationScenario(long serviceTimeMs, boolean deterministic) {
        this.serviceTimeMs = serviceTimeMs;
        this.deterministic = deterministic;
    }

    /**
     * Runs the saturation sweep.
     *
     * <p>Each utilization level runs the QueueSimulator for {@code args.duration()}.
     * The service rate μ = 1000.0 / serviceTimeMs (requests per second for 1 worker).
     *
     * @param args CLI args; {@code duration} controls how long each utilization level runs
     * @return one SaturationPoint per utilization level, in ascending utilization order
     */
    public List<SaturationPoint> run(CliArgs args) {
        double serviceRateRps = 1000.0 / serviceTimeMs;
        List<SaturationPoint> results = new ArrayList<>(UTILIZATION_LEVELS.length);

        for (double utilization : UTILIZATION_LEVELS) {
            double arrivalRateRps = utilization * serviceRateRps;
            QueueSimulator simulator = new QueueSimulator(
                    QUEUE_CAPACITY, serviceTimeMs, WORKER_COUNT, deterministic);
            QueueRunResult result = simulator.run(args, arrivalRateRps);

            // Extract last snapshot for final percentiles (most representative)
            List<PercentileSnapshot> snaps = result.snapshots();
            PercentileSnapshot lastSnap = snaps.isEmpty()
                    ? new PercentileSnapshot(0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L)
                    : snaps.get(snaps.size() - 1);

            LittlesLawResult ll = LittlesLawCalculator.compute(
                    result.actualThroughputRps(),
                    result.meanSojournMs(),
                    result.avgQueueDepth());

            results.add(new SaturationPoint(
                    utilization,
                    arrivalRateRps,
                    result.actualThroughputRps(),
                    lastSnap.p50Ms(),
                    lastSnap.p99Ms(),
                    lastSnap.p999Ms(),
                    result.meanSojournMs(),
                    result.avgQueueDepth(),
                    result.totalRejections(),
                    ll));
        }
        return results;
    }
}
