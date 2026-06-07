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

import org.HdrHistogram.Histogram;

/**
 * Deterministic discrete-event model of a single-server FIFO service fronted by a concurrency
 * limit (admission control). It is the antidote to Series 2 Post 1's collapse: instead of
 * serving requests whose clients have already given up (service-then-discard), the gate turns
 * excess load away at the door so the server only accepts work it can finish in time.
 *
 * <p>Admission rule: a new arrival is admitted only if fewer than {@code admissionLimit}
 * requests are already in the system (waiting or in service); otherwise it is rejected
 * immediately (fail-fast). Admitted requests are served FIFO and still scored as "served late"
 * if their sojourn exceeds the client deadline - a correctly sized limit keeps the worst-case
 * wait under the deadline, so served-late approaches zero.
 *
 * <p>Choosing the limit is the design lesson: too low rejects burst traffic the server could
 * have absorbed in the following valley (wasted capacity, low utilization); too high lets the
 * backlog grow until waits exceed the deadline and goodput collapses (Post 1). The sweet spot is
 * Little's Law: {@code limit = capacity x deadline}.
 *
 * <p>Fully synthetic and single-threaded for byte-stable golden output.
 */
public final class AdmissionSimulator {

    /** Admission limit meaning "no admission control" - the Post 1 degenerate case. */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    private static final long MAX_LATENCY_MS = 60_000L;
    private static final int SIGNIFICANT_DIGITS = 3;

    private final long serviceTimeMs;
    private final long clientDeadlineMs;

    public AdmissionSimulator(long serviceTimeMs, long clientDeadlineMs) {
        this.serviceTimeMs = serviceTimeMs;
        this.clientDeadlineMs = clientDeadlineMs;
    }

    public double serverCapacityRps() {
        return 1000.0 / serviceTimeMs;
    }

    /** Little's-Law admission limit for this server: capacity x deadline (rounded). */
    public int littlesLawLimit() {
        return (int) Math.round(serverCapacityRps() * (clientDeadlineMs / 1000.0));
    }

    /**
     * Runs the demand curve through the server under the given admission limit.
     *
     * @param curve          deterministic offered-load schedule
     * @param admissionLimit max in-flight requests, or {@link #NO_LIMIT}
     * @param durationMs     run window
     */
    public AdmissionPointResult run(DemandCurve curve, int admissionLimit, long durationMs) {
        double[] arrivals = curve.arrivalTimesMs(durationMs);

        Histogram sojournHistogram = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        double serverBusyUntilMs = 0.0;
        long goodput = 0L;
        long servedLate = 0L;
        long rejected = 0L;

        // Arrivals from a single curve are already in non-decreasing time order, so a plain
        // forward pass is a valid event order for a single FIFO server.
        for (double arrivalMs : arrivals) {
            long occupancyAhead = occupancyAhead(serverBusyUntilMs, arrivalMs);
            if (occupancyAhead >= admissionLimit) {
                rejected++;
                continue;
            }

            double serviceStartMs = Math.max(arrivalMs, serverBusyUntilMs);
            double finishMs = serviceStartMs + serviceTimeMs;
            serverBusyUntilMs = finishMs;

            long sojournMs = Math.max(serviceTimeMs, Math.round(finishMs - arrivalMs));
            sojournHistogram.recordValue(Math.min(sojournMs, MAX_LATENCY_MS));
            if (sojournMs <= clientDeadlineMs) {
                goodput++;
            } else {
                servedLate++;
            }
        }

        long total = arrivals.length;
        double durationSeconds = durationMs / 1000.0;
        long served = goodput + servedLate;
        double utilizationPct = Math.min(100.0, 100.0 * served * serviceTimeMs / durationMs);

        return new AdmissionPointResult(
                admissionLimit,
                total / durationSeconds,
                goodput / durationSeconds,
                total == 0L ? 0.0 : 100.0 * rejected / total,
                total == 0L ? 0.0 : 100.0 * servedLate / total,
                sojournHistogram.getValueAtPercentile(99.0),
                utilizationPct);
    }

    private long occupancyAhead(double serverBusyUntilMs, double arrivalMs) {
        if (serverBusyUntilMs <= arrivalMs) {
            return 0L;
        }
        return (long) Math.ceil((serverBusyUntilMs - arrivalMs) / serviceTimeMs);
    }
}
