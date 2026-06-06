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
package dev.engnotes.labs.backpressure.shaping;

import dev.engnotes.labs.backpressure.admission.DemandCurve;
import dev.engnotes.labs.commons.cli.CliArgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Drives the token-bucket vs leaky-bucket experiments. Both gates are set to the same sustained
 * rate (server capacity), so they admit the same average load - the comparison is about
 * <em>how</em> that load is delivered, not how much.
 *
 * <p><strong>Experiment 1 - sweeping the burst dimension.</strong> A fixed bursty demand curve
 * is run through each gate while its burst knob (bucket size B / queue depth Q) is swept.
 * Goodput curves are near-identical; the {@code gate_delay_p99} / {@code server_wait_p99} /
 * {@code downstream_peak} columns are not. Oversizing fails on each gate's own side: a big
 * bucket floods the server until waits blow the deadline (Post 1's collapse at the server), a
 * deep queue holds requests at the gate until the deadline passes. Both knobs share the Post 2
 * deadline budget: {@code capacity x deadline = 20}.
 *
 * <p><strong>Experiment 2 - shaping vs policing.</strong> At the sweet spot, downstream rate is
 * sampled per 100ms window: the token bucket passes the spike through at line rate, the leaky
 * bucket releases a flat stream at the leak rate.
 *
 * <p>Model constants are deliberately chosen for a legible synthetic demonstration: the same
 * server as Posts 1-2 ({@code SERVICE_TIME_MS = 10}, capacity 100 rps, {@code DEADLINE_MS =
 * 200}). The curve's valley must bank more tokens than the largest swept bucket (surplus
 * {@code (100 - 20) x 1.0s = 80}) and its spike must spend them ({@code 600 rps x 200ms = 120}
 * arrivals), otherwise sizes beyond the valley surplus all behave alike and the sweep shows
 * nothing.
 */
public final class ShapingScenario {

    static final String TOKEN_BUCKET = "token-bucket";
    static final String LEAKY_BUCKET = "leaky-bucket";

    private static final long SERVICE_TIME_MS = 10L;
    private static final long DEADLINE_MS = 200L;
    private static final double GATE_RATE_RPS = 100.0;

    // Bursty demand: a long shallow valley that banks burst credit, then a sharp 6x spike that
    // spends it (avg ~117 rps against capacity 100).
    private static final double VALLEY_RPS = 20.0;
    private static final long VALLEY_MS = 1_000L;
    private static final double SPIKE_RPS = 600.0;
    private static final long SPIKE_MS = 200L;

    /** Burst dimensions swept in experiment 1 (bucket size B / queue depth Q). */
    private static final int[] BURST_SWEEP = {1, 2, 4, 8, 12, 16, 20, 28, 40, 80};

    /** Window size for the experiment 2 downstream-rate time series. */
    private static final long WINDOW_MS = 100L;

    public ShapingRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());
        ShapingSimulator simulator = new ShapingSimulator(SERVICE_TIME_MS, DEADLINE_MS);
        int sweetSpot = simulator.deadlineBurstBudget();
        DemandCurve curve = DemandCurve.bursty(VALLEY_RPS, VALLEY_MS, SPIKE_RPS, SPIKE_MS);

        List<ShapingPointResult> tokenSweep = new ArrayList<>(BURST_SWEEP.length);
        List<ShapingPointResult> leakySweep = new ArrayList<>(BURST_SWEEP.length);
        for (int burst : BURST_SWEEP) {
            tokenSweep.add(simulator.run(
                    curve, new TokenBucketGate(GATE_RATE_RPS, burst), TOKEN_BUCKET, burst, durationMs));
            leakySweep.add(simulator.run(
                    curve, new LeakyBucketGate(GATE_RATE_RPS, burst), LEAKY_BUCKET, burst, durationMs));
        }

        return new ShapingRunResult(
                simulator.serverCapacityRps(),
                GATE_RATE_RPS,
                sweetSpot,
                tokenSweep,
                leakySweep,
                windowSamples(curve, sweetSpot, durationMs));
    }

    private static List<ShapingWindowSample> windowSamples(DemandCurve curve, int burst, long durationMs) {
        double[] arrivals = curve.arrivalTimesMs(durationMs);
        double[] offered = windowRatesRps(arrivals, durationMs);
        double[] token = windowRatesRps(releases(arrivals, new TokenBucketGate(GATE_RATE_RPS, burst)), durationMs);
        double[] leaky = windowRatesRps(releases(arrivals, new LeakyBucketGate(GATE_RATE_RPS, burst)), durationMs);

        List<ShapingWindowSample> samples = new ArrayList<>(offered.length);
        for (int i = 0; i < offered.length; i++) {
            samples.add(new ShapingWindowSample(i * WINDOW_MS, offered[i], token[i], leaky[i]));
        }
        return samples;
    }

    private static double[] releases(double[] arrivals, RateGate gate) {
        double[] releases = new double[arrivals.length];
        int count = 0;
        for (double arrivalMs : arrivals) {
            OptionalDouble release = gate.offer(arrivalMs);
            if (release.isPresent()) {
                releases[count++] = release.getAsDouble();
            }
        }
        return Arrays.copyOf(releases, count);
    }

    private static double[] windowRatesRps(double[] timesMs, long durationMs) {
        double[] rates = new double[(int) (durationMs / WINDOW_MS)];
        for (double timeMs : timesMs) {
            int window = (int) (timeMs / WINDOW_MS);
            if (window < rates.length) {
                rates[window] += 1000.0 / WINDOW_MS;
            }
        }
        return rates;
    }
}
