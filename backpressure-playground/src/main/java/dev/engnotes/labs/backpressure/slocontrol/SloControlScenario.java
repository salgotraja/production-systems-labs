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

import dev.engnotes.labs.backpressure.admission.DemandCurve;
import dev.engnotes.labs.commons.cli.CliArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the two SLO-driven load-control experiments.
 *
 * <p><strong>Experiment 1 - the protection sweep.</strong> Offered load is swept across the
 * protection ceiling ({@code capacity / critical_share} = 400 rps here) for each policy. Blind
 * loses the critical SLO at the first overload; priority holds critical success at ~100% until
 * the ceiling, where critical traffic alone exceeds capacity and no policy can save it.
 *
 * <p><strong>Experiment 2 - the burst (the hero).</strong> A repeating burst curve is run
 * through both policies and per-class success is sampled per 100ms arrival window. The spike is
 * sized so its critical share (90 rps) stays under capacity: priority CAN hold the critical
 * line flat through the spike while its background line pays, and blind demonstrably doesn't.
 *
 * <p>Model constants are deliberately chosen for a legible synthetic demonstration: the same
 * server as Posts 1-4 ({@code SERVICE_TIME_MS = 10}, capacity 100 rps, {@code DEADLINE_MS =
 * 200}), critical share 25% so the ceiling lands on a round 400 rps sweep point.
 */
public final class SloControlScenario {

    private static final long SERVICE_TIME_MS = 10L;
    private static final long DEADLINE_MS = 200L;
    private static final double CRITICAL_SHARE = 0.25;

    /** Offered-load levels swept in experiment 1, straddling the 400 rps protection ceiling. */
    private static final int[] OFFERED_RPS_LEVELS = {50, 100, 150, 200, 300, 400, 500};

    // Burst demand: near-capacity valley, 4.5x spike whose critical share (90 rps) stays under
    // capacity so the priority policy has room to hold the SLO.
    private static final double VALLEY_RPS = 80.0;
    private static final long VALLEY_MS = 1_000L;
    private static final double SPIKE_RPS = 360.0;
    private static final long SPIKE_MS = 500L;

    /** Window size for the experiment 2 burst time series. */
    private static final long WINDOW_MS = 100L;

    public SloRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());
        SloControlSimulator simulator =
                new SloControlSimulator(SERVICE_TIME_MS, DEADLINE_MS, CRITICAL_SHARE);

        List<SloPointResult> sweep = new ArrayList<>(ClassPolicy.values().length * OFFERED_RPS_LEVELS.length);
        for (ClassPolicy policy : ClassPolicy.values()) {
            for (int offeredRps : OFFERED_RPS_LEVELS) {
                sweep.add(simulator.run(DemandCurve.constant(offeredRps), policy, durationMs));
            }
        }

        return new SloRunResult(
                simulator.serverCapacityRps(),
                simulator.deadlineQueueBound(),
                100.0 * CRITICAL_SHARE,
                simulator.protectionCeilingRps(),
                SloControlSimulator.SLO_TARGET_PCT,
                sweep,
                burstSamples(simulator, durationMs));
    }

    private static List<SloWindowSample> burstSamples(SloControlSimulator simulator, long durationMs) {
        DemandCurve curve = DemandCurve.bursty(VALLEY_RPS, VALLEY_MS, SPIKE_RPS, SPIKE_MS);
        SloControlSimulator.WindowOutcome blind =
                simulator.successPerWindow(curve, ClassPolicy.BLIND, durationMs, WINDOW_MS);
        SloControlSimulator.WindowOutcome priority =
                simulator.successPerWindow(curve, ClassPolicy.PRIORITY, durationMs, WINDOW_MS);

        List<SloWindowSample> samples = new ArrayList<>(blind.criticalPct().length);
        for (int w = 0; w < blind.criticalPct().length; w++) {
            samples.add(new SloWindowSample(
                    w * WINDOW_MS,
                    blind.criticalPct()[w],
                    priority.criticalPct()[w],
                    blind.backgroundPct()[w],
                    priority.backgroundPct()[w]));
        }
        return samples;
    }
}
