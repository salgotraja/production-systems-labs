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
package dev.engnotes.labs.failprop.cascade;

import org.HdrHistogram.Histogram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deterministic discrete-event model of a multi-service topology with synchronous calls,
 * used to demonstrate cascading failure for the cascading-failures experiment.
 *
 * <p>Each service is a bounded worker pool in front of an unbounded FIFO queue
 * ({@link ServiceConfig}). A request walks its route's chain ({@link RouteDemand}): it takes a
 * worker at each hop, does that hop's own work, then calls the next hop <em>while still
 * holding every upstream worker</em>. Only when the leaf completes are all the chain's workers
 * released, at the same instant. That synchronous hold is the entire cascade mechanism: a slow
 * leaf parks its caller's pool, which parks the next pool up, until a pool <em>shared with an
 * unrelated route</em> is exhausted and that route starves too.
 *
 * <p>There are no timeouts and no retries here by design - workers are held to completion no
 * matter how stale the request (Series 2's service-then-discard, writ large across services).
 * Retries are Series 3 Post 2; timeout budgets are Post 4.
 *
 * <p>Success is scored at the client: a request succeeds if it completes within
 * {@code deadlineMs} of its arrival. Only arrivals with a full deadline left inside the run
 * window are scored ({@code arrival <= duration - deadline}, the Series 2 Post 5 cutoff), so
 * rates reflect steady state rather than end-of-window artifacts. The reported p99 covers
 * scored requests that completed inside the run (late completions included); requests still
 * stuck at window end count as failures and contribute no latency sample.
 *
 * <p>Fully synthetic and single-threaded for byte-stable golden output (ADR-007): every event
 * carries a monotonic sequence id, so ties on simulated time resolve identically on every run.
 */
public final class CascadeSimulator {

    private static final long MAX_LATENCY_MS = 60_000L;
    private static final int SIGNIFICANT_DIGITS = 3;

    private static final int KIND_SAMPLE = 0;
    private static final int KIND_ARRIVAL = 1;
    private static final int KIND_WORK_DONE = 2;

    private final Map<String, ServiceConfig> services;

    public CascadeSimulator(List<ServiceConfig> services) {
        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("services must not be empty");
        }
        Map<String, ServiceConfig> byName = new LinkedHashMap<>();
        for (ServiceConfig service : services) {
            if (byName.putIfAbsent(service.name(), service) != null) {
                throw new IllegalArgumentException("duplicate service name: " + service.name());
            }
        }
        this.services = byName;
    }

    /** Per-route aggregate: overall success, p99 of completed scored requests, per-window success. */
    public record RouteOutcome(String route, double successPct, double p99Ms, List<Double> windowSuccessPct) {}

    /** Queue depth of one service sampled at each scored-window start. */
    public record ServiceQueueSeries(String service, List<Integer> queueDepth) {}

    /** Full run outcome. Routes in demand order, services in topology order. */
    public record CascadeOutcome(List<RouteOutcome> routes, List<ServiceQueueSeries> services) {}

    /**
     * Runs the demand through the topology.
     *
     * @param routes     client demand per route; chains must reference known services
     * @param durationMs run window; must exceed {@code deadlineMs} so a scoring window exists
     * @param deadlineMs client deadline a request must beat to count as a success
     * @param windowMs   per-window resolution for success rates and queue-depth samples
     */
    public CascadeOutcome run(List<RouteDemand> routes, long durationMs, long deadlineMs, long windowMs) {
        validate(routes, durationMs, deadlineMs, windowMs);

        long scoringCutoffMs = durationMs - deadlineMs;
        int windowCount = (int) (scoringCutoffMs / windowMs);

        // ---- materialize arrivals (deterministic, evenly spaced, routes phase-offset) ----
        List<double[]> arrivalsByRoute = new ArrayList<>(routes.size());
        int totalRequests = 0;
        for (int r = 0; r < routes.size(); r++) {
            double interMs = 1000.0 / routes.get(r).rateRps();
            // Equal-rate routes never share a timestamp: route r is offset by r/nth of the
            // inter-arrival gap, so the merged stream is a clean interleave.
            double offsetMs = interMs * r / routes.size();
            List<Double> times = new ArrayList<>();
            for (long k = 0; ; k++) {
                double t = offsetMs + k * interMs;
                if (t >= durationMs) {
                    break;
                }
                times.add(t);
            }
            double[] packed = new double[times.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = times.get(i);
            }
            arrivalsByRoute.add(packed);
            totalRequests += packed.length;
        }

        int[] requestRoute = new int[totalRequests];
        double[] requestArrivalMs = new double[totalRequests];
        double[] requestCompletedMs = new double[totalRequests];
        java.util.Arrays.fill(requestCompletedMs, -1.0);

        // ---- event queue: total order by (time, seq) ----
        PriorityQueue<Event> events = new PriorityQueue<>(
                Comparator.comparingDouble(Event::timeMs).thenComparingLong(Event::seq));
        long[] seq = {0L};

        // Samples first so a window-start snapshot reflects the state strictly before any
        // same-instant arrivals or completions.
        for (int w = 0; w < windowCount; w++) {
            events.add(new Event((double) w * windowMs, seq[0]++, KIND_SAMPLE, w, 0));
        }
        int requestIndex = 0;
        for (int r = 0; r < routes.size(); r++) {
            for (double arrivalMs : arrivalsByRoute.get(r)) {
                requestRoute[requestIndex] = r;
                requestArrivalMs[requestIndex] = arrivalMs;
                events.add(new Event(arrivalMs, seq[0]++, KIND_ARRIVAL, requestIndex, 0));
                requestIndex++;
            }
        }

        Map<String, ServiceState> states = new LinkedHashMap<>();
        for (ServiceConfig config : services.values()) {
            states.put(config.name(), new ServiceState(config));
        }
        List<List<String>> chains = routes.stream().map(RouteDemand::chain).toList();
        int[][] queueSamples = new int[windowCount][states.size()];

        // ---- event loop ----
        while (!events.isEmpty()) {
            Event event = events.poll();
            if (event.timeMs() > durationMs) {
                break;
            }
            switch (event.kind()) {
                case KIND_SAMPLE -> {
                    int s = 0;
                    for (ServiceState state : states.values()) {
                        queueSamples[event.requestIndex()][s++] = state.queue.size();
                    }
                }
                case KIND_ARRIVAL -> {
                    ServiceState state = states.get(chains.get(requestRoute[event.requestIndex()]).get(event.hop()));
                    if (state.busy < state.config.workers()) {
                        state.busy++;
                        events.add(new Event(
                                event.timeMs() + state.config.ownWork().at(event.timeMs()),
                                seq[0]++, KIND_WORK_DONE, event.requestIndex(), event.hop()));
                    } else {
                        state.queue.addLast(new Pending(event.requestIndex(), event.hop()));
                    }
                }
                case KIND_WORK_DONE -> {
                    List<String> chain = chains.get(requestRoute[event.requestIndex()]);
                    if (event.hop() < chain.size() - 1) {
                        // Own work done; call the next hop while every upstream worker stays held.
                        events.add(new Event(event.timeMs(), seq[0]++, KIND_ARRIVAL,
                                event.requestIndex(), event.hop() + 1));
                    } else {
                        // Leaf finished: the whole chain's workers release at this instant.
                        requestCompletedMs[event.requestIndex()] = event.timeMs();
                        for (int h = chain.size() - 1; h >= 0; h--) {
                            release(states.get(chain.get(h)), event.timeMs(), events, seq);
                        }
                    }
                }
                default -> throw new IllegalStateException("unknown event kind: " + event.kind());
            }
        }

        return score(routes, requestRoute, requestArrivalMs, requestCompletedMs,
                states, queueSamples, deadlineMs, scoringCutoffMs, windowMs, windowCount);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private record Event(double timeMs, long seq, int kind, int requestIndex, int hop) {}

    private record Pending(int requestIndex, int hop) {}

    private static final class ServiceState {
        final ServiceConfig config;
        final ArrayDeque<Pending> queue = new ArrayDeque<>();
        int busy;

        ServiceState(ServiceConfig config) {
            this.config = config;
        }
    }

    private void validate(List<RouteDemand> routes, long durationMs, long deadlineMs, long windowMs) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("routes must not be empty");
        }
        if (deadlineMs <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("deadlineMs and windowMs must be > 0");
        }
        if (durationMs <= deadlineMs) {
            throw new IllegalArgumentException(
                    "durationMs must exceed deadlineMs: the scoring window (duration - deadline) is empty");
        }
        for (RouteDemand route : routes) {
            for (String service : route.chain()) {
                if (!services.containsKey(service)) {
                    throw new IllegalArgumentException(
                            "route " + route.name() + " references unknown service: " + service);
                }
            }
        }
    }

    private static void release(ServiceState state, double timeMs, PriorityQueue<Event> events, long[] seq) {
        state.busy--;
        Pending next = state.queue.pollFirst();
        if (next != null) {
            state.busy++;
            events.add(new Event(timeMs + state.config.ownWork().at(timeMs),
                    seq[0]++, KIND_WORK_DONE, next.requestIndex(), next.hop()));
        }
    }

    private CascadeOutcome score(
            List<RouteDemand> routes,
            int[] requestRoute,
            double[] requestArrivalMs,
            double[] requestCompletedMs,
            Map<String, ServiceState> states,
            int[][] queueSamples,
            long deadlineMs,
            long scoringCutoffMs,
            long windowMs,
            int windowCount) {

        int routeCount = routes.size();
        long[] offered = new long[routeCount];
        long[] success = new long[routeCount];
        long[][] windowOffered = new long[routeCount][windowCount];
        long[][] windowSuccess = new long[routeCount][windowCount];
        Histogram[] latencies = new Histogram[routeCount];
        for (int r = 0; r < routeCount; r++) {
            latencies[r] = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
        }

        for (int i = 0; i < requestArrivalMs.length; i++) {
            double arrival = requestArrivalMs[i];
            if (arrival > scoringCutoffMs) {
                continue;
            }
            int r = requestRoute[i];
            int window = (int) (arrival / windowMs);
            offered[r]++;
            boolean ok = requestCompletedMs[i] >= 0
                    && requestCompletedMs[i] - arrival <= deadlineMs;
            if (ok) {
                success[r]++;
            }
            if (window < windowCount) {
                windowOffered[r][window]++;
                if (ok) {
                    windowSuccess[r][window]++;
                }
            }
            if (requestCompletedMs[i] >= 0) {
                long latency = Math.round(requestCompletedMs[i] - arrival);
                latencies[r].recordValue(Math.clamp(latency, 0L, MAX_LATENCY_MS));
            }
        }

        List<RouteOutcome> routeOutcomes = new ArrayList<>(routeCount);
        for (int r = 0; r < routeCount; r++) {
            List<Double> perWindow = new ArrayList<>(windowCount);
            for (int w = 0; w < windowCount; w++) {
                perWindow.add(pct(windowSuccess[r][w], windowOffered[r][w]));
            }
            routeOutcomes.add(new RouteOutcome(
                    routes.get(r).name(),
                    pct(success[r], offered[r]),
                    latencies[r].getValueAtPercentile(99.0),
                    List.copyOf(perWindow)));
        }

        List<ServiceQueueSeries> queueSeries = new ArrayList<>(states.size());
        int s = 0;
        for (String name : states.keySet()) {
            List<Integer> depths = new ArrayList<>(windowCount);
            for (int w = 0; w < windowCount; w++) {
                depths.add(queueSamples[w][s]);
            }
            queueSeries.add(new ServiceQueueSeries(name, List.copyOf(depths)));
            s++;
        }

        return new CascadeOutcome(List.copyOf(routeOutcomes), List.copyOf(queueSeries));
    }

    private static double pct(long success, long offered) {
        return offered == 0L ? 100.0 : 100.0 * success / offered;
    }
}
