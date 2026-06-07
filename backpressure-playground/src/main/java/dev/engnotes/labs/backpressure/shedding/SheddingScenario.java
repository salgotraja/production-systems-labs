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

import dev.engnotes.labs.backpressure.admission.DemandCurve;
import dev.engnotes.labs.commons.cli.CliArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the two load-shedding experiments.
 *
 * <p><strong>Experiment 1 - the sweep.</strong> Offered load is swept over constant levels
 * (Post 2's levels) for each policy. Only {@code fifo} collapses; the three real policies hold
 * goodput near capacity, and what separates them is the p99 of what they served and how long
 * they held the work they abandoned.
 *
 * <p><strong>Experiment 2 - the hangover (the hero).</strong> A repeating burst curve is run
 * through every policy and the p99 of completed work is sampled per 100ms window. FIFO keeps
 * paying for each spike long after it ended (it drains stale backlog in order), {@code lifo}
 * stays fresh throughout, {@code tail-drop} and {@code expire} cap the damage near the deadline.
 *
 * <p>Model constants are deliberately chosen for a legible synthetic demonstration: the same
 * server as Posts 1-3 ({@code SERVICE_TIME_MS = 10}, capacity 100 rps, {@code DEADLINE_MS =
 * 200}). The hangover curve spikes well past capacity for 500ms (a backlog of ~250) over a
 * near-capacity 80 rps valley, so unmanaged backlog far outlives the spike that caused it.
 */
public final class SheddingScenario {

    private static final long SERVICE_TIME_MS = 10L;
    private static final long DEADLINE_MS = 200L;

    /** Offered-load levels swept in experiment 1, in rps. */
    private static final int[] OFFERED_RPS_LEVELS = {50, 75, 100, 125, 150, 200, 300};

    // Hangover demand: a near-capacity valley with a sharp 6x spike, repeating every 1.5s.
    private static final double VALLEY_RPS = 80.0;
    private static final long VALLEY_MS = 1_000L;
    private static final double SPIKE_RPS = 600.0;
    private static final long SPIKE_MS = 500L;

    /** Window size for the experiment 2 hangover time series. */
    private static final long WINDOW_MS = 100L;

    public ShedRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());
        ShedSimulator simulator = new ShedSimulator(SERVICE_TIME_MS, DEADLINE_MS);

        List<ShedPointResult> sweep = new ArrayList<>(ShedPolicy.values().length * OFFERED_RPS_LEVELS.length);
        for (ShedPolicy policy : ShedPolicy.values()) {
            for (int offeredRps : OFFERED_RPS_LEVELS) {
                sweep.add(simulator.run(DemandCurve.constant(offeredRps), policy, durationMs));
            }
        }

        return new ShedRunResult(
                simulator.serverCapacityRps(),
                simulator.deadlineQueueBound(),
                sweep,
                hangoverSamples(simulator, durationMs));
    }

    private static List<ShedWindowSample> hangoverSamples(ShedSimulator simulator, long durationMs) {
        DemandCurve curve = DemandCurve.bursty(VALLEY_RPS, VALLEY_MS, SPIKE_RPS, SPIKE_MS);
        double[] fifo = simulator.servedP99PerWindow(curve, ShedPolicy.FIFO, durationMs, WINDOW_MS);
        double[] tailDrop = simulator.servedP99PerWindow(curve, ShedPolicy.TAIL_DROP, durationMs, WINDOW_MS);
        double[] expire = simulator.servedP99PerWindow(curve, ShedPolicy.EXPIRE, durationMs, WINDOW_MS);
        double[] lifo = simulator.servedP99PerWindow(curve, ShedPolicy.LIFO, durationMs, WINDOW_MS);

        List<ShedWindowSample> samples = new ArrayList<>(fifo.length);
        for (int i = 0; i < fifo.length; i++) {
            samples.add(new ShedWindowSample(i * WINDOW_MS, fifo[i], tailDrop[i], expire[i], lifo[i]));
        }
        return samples;
    }
}
