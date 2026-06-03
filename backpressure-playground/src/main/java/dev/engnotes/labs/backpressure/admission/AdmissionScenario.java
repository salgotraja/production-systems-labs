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

import dev.engnotes.labs.commons.cli.CliArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the two admission-control experiments.
 *
 * <p><strong>Experiment 1 - choosing the limit.</strong> A fixed bursty demand curve is run
 * through the server while the admission limit is swept from very tight to effectively none.
 * Goodput peaks at a sweet spot near the Little's-Law limit ({@code capacity x deadline}): below
 * it, burst traffic is rejected that the valley could have served (low utilization); above it,
 * the backlog grows until waits exceed the deadline and goodput collapses (Post 1).
 *
 * <p><strong>Experiment 2 - the payoff.</strong> Offered load is swept with no control and again
 * at the Little's-Law limit, showing the Post 1 collapse cliff turned back into a plateau at
 * capacity.
 *
 * <p>Model constants are deliberately chosen for a legible synthetic demonstration:
 * {@code SERVICE_TIME_MS = 10} (capacity 100 rps), {@code DEADLINE_MS = 200} (Little's-Law limit
 * 20). The bursty curve averages ~133 rps with 3x spikes and half-capacity valleys, so the
 * sweet spot and both failure modes are all visible in one sweep.
 */
public final class AdmissionScenario {

    private static final long SERVICE_TIME_MS = 10L;
    private static final long DEADLINE_MS = 200L;

    // Bursty demand: half-capacity valley, then a 3x spike, repeating every 300ms (avg ~133 rps).
    private static final double VALLEY_RPS = 50.0;
    private static final long VALLEY_MS = 200L;
    private static final double SPIKE_RPS = 300.0;
    private static final long SPIKE_MS = 100L;

    /** Admission limits swept in experiment 1. {@code NO_LIMIT} is appended as the no-control case. */
    private static final int[] LIMIT_SWEEP = {1, 2, 4, 8, 12, 16, 20, 28, 40, 80, 200};

    /** Offered-load levels swept in experiment 2, in rps. */
    private static final int[] OFFERED_RPS_LEVELS = {50, 75, 100, 125, 150, 200, 300};

    public AdmissionRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());
        AdmissionSimulator simulator = new AdmissionSimulator(SERVICE_TIME_MS, DEADLINE_MS);
        int sweetSpot = simulator.littlesLawLimit();

        DemandCurve bursty = DemandCurve.bursty(VALLEY_RPS, VALLEY_MS, SPIKE_RPS, SPIKE_MS);
        List<AdmissionPointResult> limitSweep = new ArrayList<>(LIMIT_SWEEP.length + 1);
        for (int limit : LIMIT_SWEEP) {
            limitSweep.add(simulator.run(bursty, limit, durationMs));
        }
        limitSweep.add(simulator.run(bursty, AdmissionSimulator.NO_LIMIT, durationMs));

        List<AdmissionPointResult> offeredNoControl = new ArrayList<>(OFFERED_RPS_LEVELS.length);
        List<AdmissionPointResult> offeredLimited = new ArrayList<>(OFFERED_RPS_LEVELS.length);
        for (int offeredRps : OFFERED_RPS_LEVELS) {
            DemandCurve flat = DemandCurve.constant(offeredRps);
            offeredNoControl.add(simulator.run(flat, AdmissionSimulator.NO_LIMIT, durationMs));
            offeredLimited.add(simulator.run(flat, sweetSpot, durationMs));
        }

        return new AdmissionRunResult(
                simulator.serverCapacityRps(), sweetSpot, limitSweep, offeredNoControl, offeredLimited);
    }
}
