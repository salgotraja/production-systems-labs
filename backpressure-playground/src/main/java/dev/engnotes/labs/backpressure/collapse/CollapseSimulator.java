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
package dev.engnotes.labs.backpressure.collapse;

import org.HdrHistogram.Histogram;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Deterministic discrete-event model of a single-server FIFO service with no admission
 * control, used to demonstrate congestion collapse for the load-collapse experiment.
 *
 * <p>The model is fully synthetic and single-threaded (no wall clock, no virtual threads),
 * so a {@code --deterministic} run produces byte-identical output every time (the
 * golden-file contract). It mirrors the deterministic discrete-event approach proven in
 * Series 1's {@code QueueSimulator}.
 *
 * <p><strong>Why collapse happens (the mechanism this class encodes):</strong> the server
 * advances its busy clock for <em>every</em> admitted request, including ones whose client
 * has already given up. A request served after its deadline still consumed a full service
 * slot, but delivers nothing. Under sustained overload the backlog grows without bound, so
 * wait time climbs past the client deadline and the server spends an ever-larger fraction of
 * its fixed capacity on work that is already dead on arrival. Goodput (requests completed
 * within deadline) collapses below capacity instead of plateauing at it.
 *
 * <p>With {@code retriesEnabled}, a request that times out (or is rejected when the queue is
 * full) is re-sent up to {@code maxRetries} times. Retries pile fresh load onto an already
 * saturated server, so the collapse arrives sooner and runs deeper - the retry-storm death
 * spiral. The fix (admission control) is the subject of Series 2 Post 2; this experiment only
 * shows the failure.
 */
final class CollapseSimulator {

    private static final long MAX_LATENCY_MS = 60_000L;
    private static final int SIGNIFICANT_DIGITS = 3;

    private final long serviceTimeMs;
    private final int queueCapacity;
    private final long clientDeadlineMs;
    private final int maxRetries;
    private final long retryBackoffMs;

    /**
     * @param serviceTimeMs    fixed time to service one request; server capacity is
     *                         {@code mu = 1000 / serviceTimeMs} requests per second
     * @param queueCapacity    maximum requests waiting or in service before new arrivals are
     *                         rejected; Post 1 sets this effectively unbounded to isolate
     *                         deadline-driven collapse from queue-full rejection, so the
     *                         rejection path here is retained for the finite-queue and
     *                         admission-control experiments later in Series 2
     * @param clientDeadlineMs a request whose sojourn (wait + service) exceeds this is wasted:
     *                         the server still finishes it, but the client has given up
     * @param maxRetries       maximum re-sends per logical request when retries are enabled
     * @param retryBackoffMs   delay a client waits before re-sending after a timeout or rejection
     */
    CollapseSimulator(
            long serviceTimeMs,
            int queueCapacity,
            long clientDeadlineMs,
            int maxRetries,
            long retryBackoffMs) {
        this.serviceTimeMs = serviceTimeMs;
        this.queueCapacity = queueCapacity;
        this.clientDeadlineMs = clientDeadlineMs;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
    }

    double serverCapacityRps() {
        return 1000.0 / serviceTimeMs;
    }

    /**
     * Runs one offered-load level for the given window and returns its aggregate result.
     *
     * @param offeredRps     original (first-attempt) arrival rate in requests per second
     * @param durationMs     length of the simulated window in milliseconds
     * @param retriesEnabled whether timed-out / rejected requests are re-sent
     */
    LoadLevelResult run(int offeredRps, long durationMs, boolean retriesEnabled) {
        // Monotonic sequence id makes the event ordering a total order, so ties on arrival
        // time resolve identically on every run (byte-stable golden output).
        long[] seq = {0L};
        PriorityQueue<Arrival> events = new PriorityQueue<>(
                Comparator.comparingDouble(Arrival::timeMs).thenComparingLong(Arrival::seq));

        double interArrivalMs = 1000.0 / offeredRps;
        for (long k = 0; ; k++) {
            double arrivalMs = k * interArrivalMs;
            if (arrivalMs >= durationMs) {
                break;
            }
            events.add(new Arrival(arrivalMs, 0, seq[0]++));
        }

        Histogram sojournHistogram = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        double serverBusyUntilMs = 0.0;
        long processed = 0L;
        long goodput = 0L;
        long wastedServed = 0L;
        long rejected = 0L;
        long occupancySum = 0L;

        while (!events.isEmpty()) {
            Arrival arrival = events.poll();
            processed++;

            long occupancyAhead = occupancyAhead(serverBusyUntilMs, arrival.timeMs());
            occupancySum += occupancyAhead;

            if (occupancyAhead >= queueCapacity) {
                rejected++;
                scheduleRetry(events, seq, retriesEnabled, arrival,
                        arrival.timeMs() + retryBackoffMs, durationMs);
                continue;
            }

            double serviceStartMs = Math.max(arrival.timeMs(), serverBusyUntilMs);
            double finishMs = serviceStartMs + serviceTimeMs;
            serverBusyUntilMs = finishMs;

            long sojournMs = Math.max(serviceTimeMs, Math.round(finishMs - arrival.timeMs()));
            sojournHistogram.recordValue(Math.min(sojournMs, MAX_LATENCY_MS));

            if (sojournMs <= clientDeadlineMs) {
                goodput++;
            } else {
                // Service-then-discard: the slot was consumed (serverBusyUntilMs advanced),
                // but the client abandoned the request at its deadline and got nothing back.
                wastedServed++;
                double giveUpMs = arrival.timeMs() + clientDeadlineMs + retryBackoffMs;
                scheduleRetry(events, seq, retriesEnabled, arrival, giveUpMs, durationMs);
            }
        }

        double durationSeconds = durationMs / 1000.0;
        double goodputRps = goodput / durationSeconds;
        double effectiveRps = processed / durationSeconds;
        double idealGoodputRps = Math.min(offeredRps, serverCapacityRps());
        long terminalFailures = wastedServed + rejected;
        double wastedPct = processed == 0L ? 0.0 : (100.0 * terminalFailures) / processed;
        double avgQueueDepth = processed == 0L ? 0.0 : (double) occupancySum / processed;

        return new LoadLevelResult(
                retriesEnabled ? "retry" : "no-retry",
                offeredRps,
                effectiveRps,
                idealGoodputRps,
                goodputRps,
                wastedPct,
                sojournHistogram.getValueAtPercentile(50.0),
                sojournHistogram.getValueAtPercentile(99.0),
                avgQueueDepth);
    }

    /**
     * Number of requests still ahead of an arrival at {@code arrivalMs} - those not yet
     * finished by the single server. Zero when the server is idle.
     */
    private long occupancyAhead(double serverBusyUntilMs, double arrivalMs) {
        if (serverBusyUntilMs <= arrivalMs) {
            return 0L;
        }
        return (long) Math.ceil((serverBusyUntilMs - arrivalMs) / serviceTimeMs);
    }

    private void scheduleRetry(
            PriorityQueue<Arrival> events,
            long[] seq,
            boolean retriesEnabled,
            Arrival arrival,
            double retryTimeMs,
            long durationMs) {
        if (!retriesEnabled || arrival.attempt() >= maxRetries || retryTimeMs >= durationMs) {
            return;
        }
        events.add(new Arrival(retryTimeMs, arrival.attempt() + 1, seq[0]++));
    }

    /** One arrival event: a first attempt ({@code attempt == 0}) or a retry. */
    private record Arrival(double timeMs, int attempt, long seq) {}
}
