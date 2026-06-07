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
import org.HdrHistogram.Histogram;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Deterministic event-loop model of a single-server service applying one {@link ShedPolicy}.
 * Posts 2-3 could score requests in a closed-form forward pass because service order was always
 * arrival order; shedding policies break that (LIFO picks newest, expiry skips at dequeue), so
 * this simulator runs an explicit queue.
 *
 * <p>Under sustained overload every real policy restores roughly the same goodput - the
 * discriminating metrics are the p99 of what got <em>served</em> (LIFO serves fresh work, the
 * FIFO-order policies serve near-deadline work) and the shed wait: how long the system holds a
 * request it ultimately abandons. Tail-drop fast-fails at 0ms, expire slow-fails around the
 * deadline, LIFO never resolves the starved (their shed wait is the rest of the window - in
 * production the client times out, but the system held the request's resources that long).
 *
 * <p>No post-window drain: the server does not start new work at or after {@code durationMs},
 * and whatever is still queued then counts as shed - so goodput, served-late, and shed shares
 * always add up over the same arrival set.
 *
 * <p>Fully synthetic and single-threaded for byte-stable golden output.
 */
public final class ShedSimulator {

    private static final long MAX_LATENCY_MS = 60_000L;
    private static final int SIGNIFICANT_DIGITS = 3;

    private final long serviceTimeMs;
    private final long clientDeadlineMs;

    public ShedSimulator(long serviceTimeMs, long clientDeadlineMs) {
        this.serviceTimeMs = serviceTimeMs;
        this.clientDeadlineMs = clientDeadlineMs;
    }

    public double serverCapacityRps() {
        return 1000.0 / serviceTimeMs;
    }

    /** Tail-drop's knob, sized to the deadline budget: {@code capacity x deadline} (Post 2's number). */
    public int deadlineQueueBound() {
        return (int) Math.round(serverCapacityRps() * (clientDeadlineMs / 1000.0));
    }

    /** Runs the demand curve through the server under the given policy and aggregates one row. */
    public ShedPointResult run(DemandCurve curve, ShedPolicy policy, long durationMs) {
        Outcome outcome = simulate(curve, policy, durationMs);

        double durationSeconds = durationMs / 1000.0;
        long served = outcome.goodput + outcome.servedLate;
        return new ShedPointResult(
                policy.label(),
                outcome.total / durationSeconds,
                outcome.goodput / durationSeconds,
                outcome.total == 0L ? 0.0 : 100.0 * outcome.shed / outcome.total,
                outcome.total == 0L ? 0.0 : 100.0 * outcome.servedLate / outcome.total,
                outcome.servedSojourns.getValueAtPercentile(99.0),
                outcome.shedWaits.getValueAtPercentile(50.0),
                served == 0L ? 0.0 : 100.0 * outcome.servedLate / served);
    }

    /**
     * The p99 sojourn of requests <em>completing</em> in each aligned window - the hangover view.
     * Windows in which a policy completed nothing report 0.
     */
    public double[] servedP99PerWindow(DemandCurve curve, ShedPolicy policy, long durationMs, long windowMs) {
        Outcome outcome = simulate(curve, policy, durationMs);

        int windowCount = (int) (durationMs / windowMs);
        Histogram[] windows = new Histogram[windowCount];
        for (int i = 0; i < outcome.servedCount; i++) {
            int window = (int) (outcome.servedFinishMs[i] / windowMs);
            if (window < windowCount) {
                if (windows[window] == null) {
                    windows[window] = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
                }
                windows[window].recordValue(clamp(outcome.servedSojournMs[i]));
            }
        }

        double[] p99 = new double[windowCount];
        for (int i = 0; i < windowCount; i++) {
            p99[i] = windows[i] == null ? 0.0 : windows[i].getValueAtPercentile(99.0);
        }
        return p99;
    }

    // -------------------------------------------------------------------------
    // Event loop
    // -------------------------------------------------------------------------

    private static final class Outcome {
        long total;
        long goodput;
        long servedLate;
        long shed;
        final Histogram servedSojourns = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        final Histogram shedWaits = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        double[] servedFinishMs;
        double[] servedSojournMs;
        int servedCount;
    }

    private Outcome simulate(DemandCurve curve, ShedPolicy policy, long durationMs) {
        double[] arrivals = curve.arrivalTimesMs(durationMs);
        int queueBound = deadlineQueueBound();

        Outcome outcome = new Outcome();
        outcome.total = arrivals.length;
        outcome.servedFinishMs = new double[arrivals.length];
        outcome.servedSojournMs = new double[arrivals.length];

        ArrayDeque<Double> queue = new ArrayDeque<>();
        double serverFreeMs = 0.0;
        int next = 0;

        while ((next < arrivals.length || !queue.isEmpty()) && serverFreeMs < durationMs) {
            if (queue.isEmpty()) {
                double arrivalMs = arrivals[next];
                admit(queue, arrivalMs, serverFreeMs > arrivalMs, policy, queueBound, outcome);
                serverFreeMs = Math.max(serverFreeMs, arrivalMs);
                next++;
                continue;
            }
            // Everything arriving while the server was busy joins the queue before the pick.
            while (next < arrivals.length && arrivals[next] <= serverFreeMs) {
                admit(queue, arrivals[next], arrivals[next] < serverFreeMs, policy, queueBound, outcome);
                next++;
            }

            double arrivalMs = policy.pickNewest() ? queue.pollLast() : queue.pollFirst();
            double waitMs = serverFreeMs - arrivalMs;
            if (policy.dequeueExpiry() && waitMs + serviceTimeMs > clientDeadlineMs) {
                outcome.shedWaits.recordValue(clamp(waitMs));
                outcome.shed++;
                continue;
            }

            double finishMs = serverFreeMs + serviceTimeMs;
            serverFreeMs = finishMs;
            double sojournMs = finishMs - arrivalMs;
            outcome.servedSojourns.recordValue(clamp(sojournMs));
            outcome.servedFinishMs[outcome.servedCount] = finishMs;
            outcome.servedSojournMs[outcome.servedCount] = sojournMs;
            outcome.servedCount++;
            if (Math.round(sojournMs) <= clientDeadlineMs) {
                outcome.goodput++;
            } else {
                outcome.servedLate++;
            }
        }

        // Window closed: late arrivals still face the door, then everything queued is shed.
        while (next < arrivals.length) {
            admit(queue, arrivals[next], arrivals[next] < serverFreeMs, policy, queueBound, outcome);
            next++;
        }
        for (double arrivalMs : queue) {
            outcome.shedWaits.recordValue(clamp(durationMs - arrivalMs));
            outcome.shed++;
        }

        outcome.servedFinishMs = Arrays.copyOf(outcome.servedFinishMs, outcome.servedCount);
        outcome.servedSojournMs = Arrays.copyOf(outcome.servedSojournMs, outcome.servedCount);
        return outcome;
    }

    /**
     * Door admission. The bound counts in-system occupancy (queued plus in-service, Post 2's
     * semantics): an admitted request has at most {@code queueBound - 1} service slots ahead of
     * it, so its sojourn never exceeds {@code queueBound x serviceTime} - exactly the deadline
     * when the bound is sized to the deadline budget.
     */
    private void admit(ArrayDeque<Double> queue, double arrivalMs, boolean serverBusy,
                       ShedPolicy policy, int queueBound, Outcome outcome) {
        if (policy.boundedQueue() && queue.size() + (serverBusy ? 1 : 0) >= queueBound) {
            outcome.shedWaits.recordValue(0L);
            outcome.shed++;
            return;
        }
        queue.addLast(arrivalMs);
    }

    private static long clamp(double valueMs) {
        return Math.clamp(Math.round(valueMs), 0L, MAX_LATENCY_MS);
    }
}
