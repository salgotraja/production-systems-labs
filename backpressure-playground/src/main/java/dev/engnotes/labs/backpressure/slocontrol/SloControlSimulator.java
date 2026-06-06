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
import org.HdrHistogram.Histogram;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Deterministic event-loop model of the assembled bounded system - in-system door bound (Post 2)
 * plus dequeue expiry (Post 4) - serving two criticality classes under a {@link ClassPolicy}.
 *
 * <p>Because the bound caps the worst sojourn at the deadline and expiry discards doomed work
 * free of charge, <em>every served request finishes inside the deadline regardless of policy or
 * load</em>: served-late is structurally zero and the critical p99 reads ~deadline-flat for both
 * policies. A latency SLO therefore cannot tell the policies apart. The SLO that can is a
 * success-rate SLO - the fraction of a class's arrivals that complete in deadline - and that is
 * what this simulator scores per class.
 *
 * <p>Arrivals come from one demand curve and are classified critical/background by a
 * <em>fixed-seed</em> pseudo-random interleave at the configured critical share. A periodic
 * every-Nth pattern phase-locks with the service cadence at harmonic offered rates (every 2nd
 * arrival admitted x every 4th critical = the door systematically hits or misses the critical
 * class), producing resonance artifacts no real traffic mix has. The fixed seed keeps the
 * output fully deterministic and byte-stable.
 *
 * <p>Success is scored only for arrivals with a full deadline left inside the run window
 * ({@code arrival <= duration - deadline}); later arrivals cannot possibly resolve in-window
 * and counting them would charge a measurement artifact to the policy. The door bound
 * guarantees every scored arrival is fully resolved (served, expired, evicted, or rejected)
 * before the window closes.
 *
 * <p>Fully synthetic and single-threaded for byte-stable golden output.
 */
public final class SloControlSimulator {

    /** The success-rate SLO: at least this share of critical arrivals must complete in deadline. */
    public static final double SLO_TARGET_PCT = 99.0;

    private static final long MAX_LATENCY_MS = 60_000L;
    private static final int SIGNIFICANT_DIGITS = 3;
    // Chosen so the stream's realized critical share tracks the nominal 25% even over the short
    // prefixes that low offered rates sample (~24.6-24.8% at 240/960/2400 arrivals).
    private static final long CLASSIFICATION_SEED = 23L;

    private final long serviceTimeMs;
    private final long clientDeadlineMs;
    private final double criticalShare;

    public SloControlSimulator(long serviceTimeMs, long clientDeadlineMs, double criticalShare) {
        if (criticalShare <= 0.0 || criticalShare >= 1.0) {
            throw new IllegalArgumentException("criticalShare must be in (0, 1)");
        }
        this.serviceTimeMs = serviceTimeMs;
        this.clientDeadlineMs = clientDeadlineMs;
        this.criticalShare = criticalShare;
    }

    public double serverCapacityRps() {
        return 1000.0 / serviceTimeMs;
    }

    /** The door bound, sized to the deadline budget: {@code capacity x deadline} (Post 2's number). */
    public int deadlineQueueBound() {
        return (int) Math.round(serverCapacityRps() * (clientDeadlineMs / 1000.0));
    }

    /**
     * The protection ceiling: total offered load beyond which even a perfect priority policy
     * cannot hold the critical SLO, because critical traffic alone exceeds capacity.
     */
    public double protectionCeilingRps() {
        return serverCapacityRps() / criticalShare;
    }

    /** Runs the demand curve under the given policy and aggregates one sweep row. */
    public SloPointResult run(DemandCurve curve, ClassPolicy policy, long durationMs) {
        Outcome outcome = simulate(curve, policy, durationMs);

        // Rates are per second of the scoring window, so a 100 rps curve reads as 100.0.
        double durationSeconds = Math.max(1L, durationMs - clientDeadlineMs) / 1000.0;
        long criticalOffered = outcome.offered(true);
        long backgroundOffered = outcome.offered(false);
        double criticalSuccessPct = successPct(outcome.success(true), criticalOffered);
        return new SloPointResult(
                policy.label(),
                (criticalOffered + backgroundOffered) / durationSeconds,
                criticalOffered / durationSeconds,
                criticalSuccessPct,
                successPct(outcome.success(false), backgroundOffered),
                outcome.criticalSojourns.getValueAtPercentile(99.0),
                outcome.backgroundSojourns.getValueAtPercentile(99.0),
                criticalSuccessPct >= SLO_TARGET_PCT,
                (outcome.success(true) + outcome.success(false)) / durationSeconds);
    }

    /**
     * Per-class success rate by <em>arrival</em> window - the client's view of each window.
     * Windows run up to the scoring cutoff ({@code duration - deadline}); windows with no
     * arrivals of a class report 100 (vacuously met).
     */
    public WindowOutcome successPerWindow(DemandCurve curve, ClassPolicy policy, long durationMs, long windowMs) {
        Outcome outcome = simulate(curve, policy, durationMs);

        int windowCount = (int) ((durationMs - clientDeadlineMs) / windowMs);
        long[][] offered = new long[2][windowCount];
        long[][] success = new long[2][windowCount];
        for (int i = 0; i < outcome.scoredLimit; i++) {
            int window = (int) (outcome.arrivalMs[i] / windowMs);
            if (window >= windowCount) {
                continue;
            }
            int classIndex = outcome.critical[i] ? 0 : 1;
            offered[classIndex][window]++;
            if (outcome.completedInDeadline[i]) {
                success[classIndex][window]++;
            }
        }

        double[] criticalPct = new double[windowCount];
        double[] backgroundPct = new double[windowCount];
        for (int w = 0; w < windowCount; w++) {
            criticalPct[w] = successPct(success[0][w], offered[0][w]);
            backgroundPct[w] = successPct(success[1][w], offered[1][w]);
        }
        return new WindowOutcome(criticalPct, backgroundPct);
    }

    /** Per-class success rate per window: index 0 = critical, 1 = background. */
    public record WindowOutcome(double[] criticalPct, double[] backgroundPct) {}

    // -------------------------------------------------------------------------
    // Event loop
    // -------------------------------------------------------------------------

    private static final class Outcome {
        double[] arrivalMs;
        boolean[] critical;
        boolean[] completedInDeadline;
        int scoredLimit; // arrivals at index >= this had no full deadline left in the window
        final Histogram criticalSojourns = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        final Histogram backgroundSojourns = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);

        long offered(boolean wantCritical) {
            long count = 0L;
            for (int i = 0; i < scoredLimit; i++) {
                if (critical[i] == wantCritical) {
                    count++;
                }
            }
            return count;
        }

        long success(boolean wantCritical) {
            long count = 0L;
            for (int i = 0; i < scoredLimit; i++) {
                if (critical[i] == wantCritical && completedInDeadline[i]) {
                    count++;
                }
            }
            return count;
        }
    }

    private Outcome simulate(DemandCurve curve, ClassPolicy policy, long durationMs) {
        double[] arrivals = curve.arrivalTimesMs(durationMs);
        int queueBound = deadlineQueueBound();

        Outcome outcome = new Outcome();
        outcome.arrivalMs = arrivals;
        outcome.critical = classify(arrivals.length);
        outcome.completedInDeadline = new boolean[arrivals.length];
        outcome.scoredLimit = scoredLimit(arrivals, durationMs - clientDeadlineMs);

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        double serverFreeMs = 0.0;
        int next = 0;

        while ((next < arrivals.length || !queue.isEmpty()) && serverFreeMs < durationMs) {
            if (queue.isEmpty()) {
                admit(queue, next, serverFreeMs > arrivals[next], policy, queueBound, outcome);
                serverFreeMs = Math.max(serverFreeMs, arrivals[next]);
                next++;
                continue;
            }
            // Everything arriving while the server was busy joins the queue before the pick.
            while (next < arrivals.length && arrivals[next] <= serverFreeMs) {
                admit(queue, next, arrivals[next] < serverFreeMs, policy, queueBound, outcome);
                next++;
            }

            int index = queue.pollFirst();
            double waitMs = serverFreeMs - arrivals[index];
            if (waitMs + serviceTimeMs > clientDeadlineMs) {
                continue; // dequeue expiry: doomed work discarded free (Post 4)
            }

            double finishMs = serverFreeMs + serviceTimeMs;
            serverFreeMs = finishMs;
            outcome.completedInDeadline[index] = true; // sojourn <= deadline by bound + expiry
            if (index < outcome.scoredLimit) {
                Histogram sojourns =
                        outcome.critical[index] ? outcome.criticalSojourns : outcome.backgroundSojourns;
                sojourns.recordValue(Math.clamp(Math.round(finishMs - arrivals[index]), 0L, MAX_LATENCY_MS));
            }
        }
        // Arrivals never resolved inside the window simply count as failures.

        return outcome;
    }

    /**
     * Door admission. Both policies share the in-system bound (queue + in-service, Post 2
     * semantics); {@link ClassPolicy#PRIORITY} additionally lets a critical arrival evict the
     * newest queued background request when full.
     */
    private void admit(ArrayDeque<Integer> queue, int index, boolean serverBusy,
                       ClassPolicy policy, int queueBound, Outcome outcome) {
        if (queue.size() + (serverBusy ? 1 : 0) < queueBound) {
            queue.addLast(index);
            return;
        }
        if (policy == ClassPolicy.PRIORITY && outcome.critical[index]) {
            Iterator<Integer> newestFirst = queue.descendingIterator();
            while (newestFirst.hasNext()) {
                if (!outcome.critical[newestFirst.next()]) {
                    newestFirst.remove(); // evicted background counts as a failure
                    queue.addLast(index);
                    return;
                }
            }
        }
        // Rejected at the door: completedInDeadline stays false.
    }

    /**
     * Fixed-seed pseudo-random classification at the critical share. Deterministic and
     * byte-stable ({@code java.util.Random} is algorithm-specified), but uncorrelated with the
     * service cadence - an evenly-interleaved every-Nth pattern phase-locks with the door at
     * harmonic offered rates and corrupts the per-class split.
     */
    private boolean[] classify(int count) {
        java.util.Random random = new java.util.Random(CLASSIFICATION_SEED);
        boolean[] critical = new boolean[count];
        for (int i = 0; i < count; i++) {
            critical[i] = random.nextDouble() < criticalShare;
        }
        return critical;
    }

    private static int scoredLimit(double[] arrivals, long cutoffMs) {
        int limit = 0;
        while (limit < arrivals.length && arrivals[limit] <= cutoffMs) {
            limit++;
        }
        return limit;
    }

    private static double successPct(long success, long offered) {
        return offered == 0L ? 100.0 : 100.0 * success / offered;
    }
}
