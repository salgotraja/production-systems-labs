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
import org.HdrHistogram.Histogram;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Deterministic discrete-event model of a single-server FIFO service fronted by a {@link
 * RateGate}. Posts 1 and 2 established the server and its failure mode (service-then-discard:
 * the server burns a slot on every admitted request, even past-deadline ones). This experiment
 * varies only the gate in front of it.
 *
 * <p>Both gates bound the same average admitted rate, so goodput alone cannot tell them apart.
 * What differs is <em>where</em> the burst and the wait land, and that is what this simulator
 * measures: gate delay (release - arrival), server wait (service start - release), and the peak
 * downstream rate the server sees over any aligned 100ms window.
 *
 * <p>Fully synthetic and single-threaded for byte-stable golden output.
 */
public final class ShapingSimulator {

    private static final long MAX_LATENCY_MS = 60_000L;
    private static final int SIGNIFICANT_DIGITS = 3;
    private static final long PEAK_WINDOW_MS = 100L;

    private final long serviceTimeMs;
    private final long clientDeadlineMs;

    public ShapingSimulator(long serviceTimeMs, long clientDeadlineMs) {
        this.serviceTimeMs = serviceTimeMs;
        this.clientDeadlineMs = clientDeadlineMs;
    }

    public double serverCapacityRps() {
        return 1000.0 / serviceTimeMs;
    }

    /** Deadline-derived burst-dimension sweet spot: {@code capacity x deadline} (Post 2's number). */
    public int deadlineBurstBudget() {
        return (int) Math.round(serverCapacityRps() * (clientDeadlineMs / 1000.0));
    }

    /**
     * Runs the demand curve through the gate and the server.
     *
     * @param curve         deterministic offered-load schedule
     * @param gate          fresh gate instance (gates are stateful, single-run)
     * @param limiter       gate discipline label for the result row
     * @param burstCapacity the swept knob value, echoed into the result row
     * @param durationMs    run window
     */
    public ShapingPointResult run(
            DemandCurve curve, RateGate gate, String limiter, int burstCapacity, long durationMs) {

        double[] arrivals = curve.arrivalTimesMs(durationMs);

        Histogram gateDelayHistogram = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        Histogram serverWaitHistogram = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        Map<Long, Integer> releasesPerWindow = new HashMap<>();
        double serverBusyUntilMs = 0.0;
        long goodput = 0L;
        long servedLate = 0L;
        long rejected = 0L;

        // Release times from either gate are non-decreasing in arrival order, so a forward pass
        // is a valid event order for the single FIFO server.
        for (double arrivalMs : arrivals) {
            OptionalDouble release = gate.offer(arrivalMs);
            if (release.isEmpty()) {
                rejected++;
                continue;
            }

            double releaseMs = release.getAsDouble();
            releasesPerWindow.merge((long) (releaseMs / PEAK_WINDOW_MS), 1, Integer::sum);

            double serviceStartMs = Math.max(releaseMs, serverBusyUntilMs);
            double finishMs = serviceStartMs + serviceTimeMs;
            serverBusyUntilMs = finishMs;

            gateDelayHistogram.recordValue(clamp(releaseMs - arrivalMs));
            serverWaitHistogram.recordValue(clamp(serviceStartMs - releaseMs));
            long sojournMs = Math.max(serviceTimeMs, Math.round(finishMs - arrivalMs));
            if (sojournMs <= clientDeadlineMs) {
                goodput++;
            } else {
                servedLate++;
            }
        }

        long total = arrivals.length;
        double durationSeconds = durationMs / 1000.0;
        int peakWindowCount = releasesPerWindow.values().stream().max(Integer::compare).orElse(0);

        return new ShapingPointResult(
                limiter,
                burstCapacity,
                total / durationSeconds,
                goodput / durationSeconds,
                total == 0L ? 0.0 : 100.0 * rejected / total,
                total == 0L ? 0.0 : 100.0 * servedLate / total,
                gateDelayHistogram.getValueAtPercentile(99.0),
                serverWaitHistogram.getValueAtPercentile(99.0),
                peakWindowCount * (1000.0 / PEAK_WINDOW_MS));
    }

    private static long clamp(double valueMs) {
        return Math.clamp(Math.round(valueMs), 0L, MAX_LATENCY_MS);
    }
}
