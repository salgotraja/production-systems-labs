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
package dev.engnotes.labs.failprop.breaker;

import dev.engnotes.labs.failprop.cascade.RouteDemand;
import dev.engnotes.labs.failprop.cascade.ServiceConfig;
import org.HdrHistogram.Histogram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deterministic discrete-event model of multiple service routes whose call edges may be
 * protected by {@link CircuitBreaker}s, used for the circuit-breaker experiment. It extends
 * Post 2's retry-storm machine ({@code retrystorm.RetryStormSimulator}, deliberately copied so
 * Post 2's golden contract stays frozen) in three ways:
 * <ul>
 *   <li><strong>Multiple routes</strong> share services - Post 1's blast-radius topology, so
 *       the experiment can measure what a breaker does for the route that never touches the
 *       failure.</li>
 *   <li><strong>Breakers gate edges.</strong> Each {@code caller->callee} edge may carry a
 *       breaker; a rejected attempt fails immediately without spawning downstream work, still
 *       consuming one of the caller's retry attempts. Rejections record no outcome; the
 *       caller's observed timeouts and responses do.</li>
 *   <li><strong>Fail-fast responses propagate.</strong> With breakers, a callee can exhaust
 *       its (rejected) attempts faster than its caller's timeout, so the fail-response path -
 *       structurally unreachable in Post 2's uniform-timeout world and asserted there - is
 *       live here: the caller observes the failure response and retries or fails upward.</li>
 * </ul>
 *
 * <p>Scoring follows Post 2: per-route client success against {@code deadlineMs} with the
 * Series 2 Post 5 cutoff; resolution percentiles cover scored roots that resolved in-window.
 * A single-route run with an empty breaker bank reproduces Post 2's numbers exactly - pinned
 * by test, the same cross-check discipline as Series 2's {@code AdmissionSimulator@NO_LIMIT}.
 *
 * <p>Fully synthetic and single-threaded for byte-stable golden output (ADR-007).
 */
public final class BreakerStormSimulator {

    private static final long MAX_LATENCY_MS = 60_000L;
    private static final int SIGNIFICANT_DIGITS = 3;

    private static final int KIND_ARRIVAL = 0;
    private static final int KIND_WORK_DONE = 1;
    private static final int KIND_CHILD_TIMEOUT = 2;
    private static final int KIND_BEGIN_ATTEMPT = 3;

    private static final byte STATE_QUEUED = 0;
    private static final byte STATE_WORKING = 1;
    private static final byte STATE_WAITING = 2;
    private static final byte STATE_DONE = 3;

    private final Map<String, ServiceConfig> services;
    private final RetryPolicyView policy;
    private final Map<String, EdgeBreaker> breakers;
    private final TimeoutBudget budget;
    private final Map<String, int[]> bulkhead;

    /**
     * Local mirror of Post 2's retry policy fields (kept module-internal so this package does
     * not depend on {@code retrystorm}).
     */
    public record RetryPolicyView(int attemptsPerHop, long perCallTimeoutMs, long backoffMs) {
        public RetryPolicyView {
            if (attemptsPerHop < 1 || perCallTimeoutMs <= 0 || backoffMs < 0) {
                throw new IllegalArgumentException("invalid retry policy");
            }
        }
    }

    /**
     * @param services topology services
     * @param policy   per-hop retry policy applied by every non-leaf hop
     * @param breakers breaker per protected edge, keyed {@code "caller->callee"}; empty map
     *                 means no protection anywhere (the naive baseline)
     */
    public BreakerStormSimulator(
            List<ServiceConfig> services, RetryPolicyView policy, Map<String, EdgeBreaker> breakers) {
        this(services, policy, breakers, null, Map.of());
    }

    public BreakerStormSimulator(
            List<ServiceConfig> services, RetryPolicyView policy, Map<String, EdgeBreaker> breakers,
            TimeoutBudget budget) {
        this(services, policy, breakers, budget, Map.of());
    }

    /**
     * @param budget   propagated deadline that gates downstream calls, or {@code null} for no
     *                 budgeting (uncoordinated per-call timeouts only - the Posts 2-3 behaviour)
     * @param bulkhead per-route concurrency limits keyed by service name (the limit array is
     *                 indexed by route order); an absent service runs as a single shared pool,
     *                 and an empty map means no bulkheading anywhere
     */
    public BreakerStormSimulator(
            List<ServiceConfig> services, RetryPolicyView policy, Map<String, EdgeBreaker> breakers,
            TimeoutBudget budget, Map<String, int[]> bulkhead) {
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
        this.policy = policy;
        this.breakers = Map.copyOf(breakers);
        this.budget = budget;
        Map<String, int[]> copy = new LinkedHashMap<>();
        bulkhead.forEach((service, limits) -> copy.put(service, limits.clone()));
        this.bulkhead = copy;
    }

    /** Per-route aggregate over scored roots. */
    public record RouteResult(
            String route,
            double successPct,
            double p50ResolutionMs,
            double p99ResolutionMs,
            List<Double> windowSuccessPct) {}

    /**
     * Full run outcome. {@code leafSpawnsPastDeadline} counts leaf-service attempts that
     * <em>started</em> after their request's deadline had already passed - pure wasted work, the
     * load a propagated budget eliminates and uncoordinated timeouts leave behind.
     */
    public record BreakerOutcome(
            List<RouteResult> routes,
            Map<String, Long> attemptsByService,
            Map<String, List<Integer>> windowAttemptsByService,
            List<Integer> leafAttemptsPerRoot,
            long leafSpawnsPastDeadline,
            Map<String, List<CircuitBreaker.Transition>> breakerTransitions,
            Map<String, List<Integer>> windowBreakerState) {}

    /**
     * Runs the routes' demand through the topology.
     *
     * @param routes     client demand; equal-rate routes are phase-offset so arrivals interleave
     * @param durationMs run window; must exceed {@code deadlineMs}
     * @param deadlineMs client deadline a root must beat to count as a success
     * @param windowMs   per-window resolution
     */
    public BreakerOutcome run(List<RouteDemand> routes, long durationMs, long deadlineMs, long windowMs) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("routes must not be empty");
        }
        long[] perRoute = new long[routes.size()];
        java.util.Arrays.fill(perRoute, deadlineMs);
        return run(routes, durationMs, perRoute, windowMs);
    }

    /**
     * Runs with a <em>per-route</em> client deadline - the realistic case where a slow batch
     * endpoint and a fast interactive endpoint share a frontend pool but answer to different
     * SLAs. The success test and scoring window for each route use its own deadline.
     */
    public BreakerOutcome run(
            List<RouteDemand> routes, long durationMs, long[] routeDeadlinesMs, long windowMs) {
        if (routes == null || routeDeadlinesMs == null || routeDeadlinesMs.length != routes.size()) {
            throw new IllegalArgumentException("one deadline per route is required");
        }
        long maxDeadlineMs = java.util.Arrays.stream(routeDeadlinesMs).max().orElseThrow();
        validate(routes, durationMs, maxDeadlineMs, windowMs);

        long scoringCutoffMs = durationMs - maxDeadlineMs;
        int windowCount = (int) (scoringCutoffMs / windowMs);

        Sim sim = new Sim(routes, windowMs, windowCount);
        sim.deadlineMs = maxDeadlineMs;
        sim.durationMs = durationMs;
        sim.routeDeadlines = routeDeadlinesMs.clone();

        for (int r = 0; r < routes.size(); r++) {
            double interMs = 1000.0 / routes.get(r).rateRps();
            double offsetMs = interMs * r / routes.size();
            for (long k = 0; ; k++) {
                double t = offsetMs + k * interMs;
                if (t >= durationMs) {
                    break;
                }
                int root = sim.spawn(r, 0, -1, t);
                sim.nodes.get(root).rootArrivalMs = t;
            }
        }

        while (!sim.events.isEmpty()) {
            Event event = sim.events.poll();
            if (event.timeMs() > durationMs) {
                break;
            }
            switch (event.kind()) {
                case KIND_ARRIVAL -> sim.onArrival(event.node(), event.timeMs());
                case KIND_WORK_DONE -> sim.onWorkDone(event.node(), event.timeMs());
                case KIND_CHILD_TIMEOUT -> sim.onChildTimeout(event.node(), event.stamp(), event.timeMs());
                case KIND_BEGIN_ATTEMPT -> sim.onBeginAttempt(event.node(), event.stamp(), event.timeMs());
                default -> throw new IllegalStateException("unknown event kind: " + event.kind());
            }
        }

        return sim.score();
    }

    // -------------------------------------------------------------------------
    // Private machinery
    // -------------------------------------------------------------------------

    private record Event(double timeMs, long seq, int kind, int node, int stamp) {}

    private static final class Node {
        final int route;
        final int hop;
        final int parent;
        final int rootIndex;
        byte state = STATE_QUEUED;
        int currentAttempt;
        int activeChild = -1;
        int generation;
        boolean abandoned;
        boolean failed;
        double rootArrivalMs;
        double resolvedMs = -1.0;

        Node(int route, int hop, int parent, int rootIndex) {
            this.route = route;
            this.hop = hop;
            this.parent = parent;
            this.rootIndex = rootIndex;
        }
    }

    private static final class ServiceState {
        final ServiceConfig config;
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        int busy;

        // Bulkhead: when partitioned, each route gets a dedicated concurrency limit and its own
        // queue, so one route's slow work can exhaust only its own partition - never the share
        // a sibling route depends on. Null fields mean the service runs as a single shared pool.
        int[] partitionLimit;
        int[] partitionBusy;
        List<ArrayDeque<Integer>> partitionQueue;

        ServiceState(ServiceConfig config) {
            this.config = config;
        }

        boolean bulkheaded() {
            return partitionLimit != null;
        }
    }

    private final class Sim {
        final List<RouteDemand> routes;
        final List<List<String>> chains;
        final long windowMs;
        final int windowCount;
        final List<Node> nodes = new ArrayList<>();
        final PriorityQueue<Event> events = new PriorityQueue<>(
                Comparator.comparingDouble(Event::timeMs).thenComparingLong(Event::seq));
        final Map<String, ServiceState> states = new LinkedHashMap<>();
        final Map<String, long[]> spawnTotals = new LinkedHashMap<>();
        final Map<String, int[]> windowSpawns = new LinkedHashMap<>();
        final List<Integer> leafAttempts = new ArrayList<>(); // root index per leaf spawn
        long deadlineMs;
        long durationMs;
        long[] routeDeadlines;
        long leafSpawnsPastDeadline;
        int rootCount;
        long seq;

        Sim(List<RouteDemand> routes, long windowMs, int windowCount) {
            this.routes = routes;
            this.chains = routes.stream().map(RouteDemand::chain).toList();
            this.windowMs = windowMs;
            this.windowCount = windowCount;
            int routeCount = routes.size();
            for (ServiceConfig config : services.values()) {
                ServiceState state = new ServiceState(config);
                int[] limits = bulkhead.get(config.name());
                if (limits != null) {
                    if (limits.length != routeCount) {
                        throw new IllegalArgumentException(
                                "bulkhead for " + config.name() + " must have one limit per route");
                    }
                    state.partitionLimit = limits;
                    state.partitionBusy = new int[routeCount];
                    state.partitionQueue = new ArrayList<>(routeCount);
                    for (int r = 0; r < routeCount; r++) {
                        state.partitionQueue.add(new ArrayDeque<>());
                    }
                }
                states.put(config.name(), state);
                spawnTotals.put(config.name(), new long[1]);
                windowSpawns.put(config.name(), new int[windowCount]);
            }
        }

        int spawn(int route, int hop, int parent, double timeMs) {
            int rootIndex = parent == -1 ? rootCount++ : nodes.get(parent).rootIndex;
            Node node = new Node(route, hop, parent, rootIndex);
            if (parent != -1) {
                // The request's arrival travels down the tree so any node can measure its age
                // against the deadline - the basis for both budgeting and the wasted-work count.
                node.rootArrivalMs = nodes.get(parent).rootArrivalMs;
            }
            nodes.add(node);
            int index = nodes.size() - 1;
            String service = chains.get(route).get(hop);
            spawnTotals.get(service)[0]++;
            if (hop == chains.get(route).size() - 1) {
                leafAttempts.add(rootIndex);
                if (timeMs - node.rootArrivalMs > deadlineMs) {
                    leafSpawnsPastDeadline++;
                }
            }
            int window = (int) (timeMs / windowMs);
            if (window >= 0 && window < windowCount) {
                windowSpawns.get(service)[window]++;
            }
            events.add(new Event(timeMs, seq++, KIND_ARRIVAL, index, 0));
            return index;
        }

        void onArrival(int index, double timeMs) {
            Node node = nodes.get(index);
            ServiceState state = states.get(chains.get(node.route).get(node.hop));
            if (state.bulkheaded()) {
                if (state.partitionBusy[node.route] < state.partitionLimit[node.route]) {
                    startWork(state, index, timeMs);
                } else {
                    state.partitionQueue.get(node.route).addLast(index);
                }
            } else if (state.busy < state.config.workers()) {
                startWork(state, index, timeMs);
            } else {
                state.queue.addLast(index);
            }
        }

        void startWork(ServiceState state, int index, double timeMs) {
            int route = nodes.get(index).route;
            if (state.bulkheaded()) {
                state.partitionBusy[route]++;
                if (state.partitionBusy[route] > state.partitionLimit[route]) {
                    throw new IllegalStateException("bulkhead partition over-committed at " + state.config.name());
                }
            } else {
                state.busy++;
                if (state.busy > state.config.workers()) {
                    throw new IllegalStateException("worker pool over-committed at " + state.config.name());
                }
            }
            nodes.get(index).state = STATE_WORKING;
            events.add(new Event(timeMs + state.config.ownWork().at(timeMs),
                    seq++, KIND_WORK_DONE, index, 0));
        }

        void onWorkDone(int index, double timeMs) {
            Node node = nodes.get(index);
            if (node.hop == chains.get(node.route).size() - 1) {
                finish(index, true, timeMs);
            } else {
                node.state = STATE_WAITING;
                startAttempt(index, 1, timeMs);
            }
        }

        void startAttempt(int index, int attempt, double timeMs) {
            Node node = nodes.get(index);
            node.currentAttempt = attempt;
            node.generation++;
            if (budget != null && timeMs - node.rootArrivalMs + budget.floorMs() > budget.deadlineMs()) {
                // Deadline first: the request is already too old for a downstream call to finish
                // in time, so fail fast without spawning doomed work - and crucially without
                // consulting the breaker. Because the deadline is carried, even an abandoned
                // subtree stops here instead of running its retries to exhaustion.
                finish(index, false, timeMs);
                return;
            }
            EdgeBreaker breaker = breakerFor(node);
            if (breaker != null && !breaker.allow(timeMs)) {
                // Fail fast: the attempt is consumed without ever touching the downstream.
                // No outcome is recorded - the breaker is rejecting, not observing.
                retryOrFail(index, timeMs);
                return;
            }
            node.activeChild = spawn(node.route, node.hop + 1, index, timeMs);
            double timeoutAt = timeMs + policy.perCallTimeoutMs();
            if (budget != null) {
                // Deadline propagation: the call gets the smaller of its static timeout and the
                // request's remaining budget, so an in-flight call cannot run past the deadline
                // either. This is what caps the whole tree's resolution at the deadline rather
                // than just refusing late spawns.
                timeoutAt = Math.min(timeoutAt, node.rootArrivalMs + budget.deadlineMs());
            }
            events.add(new Event(timeoutAt, seq++, KIND_CHILD_TIMEOUT, index, node.generation));
        }

        void onChildTimeout(int index, int generation, double timeMs) {
            Node node = nodes.get(index);
            if (node.state != STATE_WAITING || node.generation != generation) {
                return;
            }
            nodes.get(node.activeChild).abandoned = true;
            node.activeChild = -1;
            node.generation++;
            reportOutcome(node, false, timeMs);
            retryOrFail(index, timeMs);
        }

        void onBeginAttempt(int index, int attempt, double timeMs) {
            Node node = nodes.get(index);
            if (node.state != STATE_WAITING) {
                throw new IllegalStateException("BEGIN_ATTEMPT on a node that is not waiting");
            }
            startAttempt(index, attempt, timeMs);
        }

        void retryOrFail(int index, double timeMs) {
            Node node = nodes.get(index);
            if (node.currentAttempt < policy.attemptsPerHop()) {
                events.add(new Event(timeMs + policy.backoffMs(),
                        seq++, KIND_BEGIN_ATTEMPT, index, node.currentAttempt + 1));
            } else {
                finish(index, false, timeMs);
            }
        }

        void finish(int index, boolean ok, double timeMs) {
            Node node = nodes.get(index);
            if (node.state == STATE_DONE) {
                throw new IllegalStateException("node resolved twice");
            }
            byte previous = node.state;
            node.state = STATE_DONE;
            node.failed = !ok;
            node.resolvedMs = timeMs;
            if (previous != STATE_QUEUED) {
                release(states.get(chains.get(node.route).get(node.hop)), node.route, timeMs);
            }
            if (node.parent >= 0 && !node.abandoned) {
                parentReact(node.parent, ok, timeMs);
            }
        }

        void parentReact(int index, boolean childOk, double timeMs) {
            Node node = nodes.get(index);
            if (node.state != STATE_WAITING) {
                throw new IllegalStateException("response delivered to a node that is not waiting");
            }
            node.activeChild = -1;
            node.generation++; // invalidate the pending timeout for this attempt
            reportOutcome(node, childOk, timeMs);
            if (childOk) {
                finish(index, true, timeMs);
            } else {
                // Reachable here (unlike Post 2's uniform-timeout world): a breaker lets a
                // callee exhaust its rejected attempts faster than this caller's timeout, so
                // the failure arrives as a fast response instead of a timeout.
                retryOrFail(index, timeMs);
            }
        }

        void release(ServiceState state, int route, double timeMs) {
            if (state.bulkheaded()) {
                state.partitionBusy[route]--;
                if (state.partitionBusy[route] < 0) {
                    throw new IllegalStateException("bulkhead partition under-committed at " + state.config.name());
                }
                Integer next = state.partitionQueue.get(route).pollFirst();
                if (next != null) {
                    startWork(state, next, timeMs);
                }
            } else {
                state.busy--;
                if (state.busy < 0) {
                    throw new IllegalStateException("worker pool under-committed at " + state.config.name());
                }
                Integer next = state.queue.pollFirst();
                if (next != null) {
                    startWork(state, next, timeMs);
                }
            }
        }

        EdgeBreaker breakerFor(Node node) {
            List<String> chain = chains.get(node.route);
            return breakers.get(chain.get(node.hop) + "->" + chain.get(node.hop + 1));
        }

        void reportOutcome(Node node, boolean ok, double timeMs) {
            EdgeBreaker breaker = breakerFor(node);
            if (breaker == null) {
                return;
            }
            if (ok) {
                breaker.onSuccess(timeMs);
            } else {
                breaker.onFailure(timeMs);
            }
        }

        BreakerOutcome score() {
            int routeCount = routes.size();
            long[] scored = new long[routeCount];
            long[] success = new long[routeCount];
            long[][] windowOffered = new long[routeCount][windowCount];
            long[][] windowSuccess = new long[routeCount][windowCount];
            Histogram[] resolutions = new Histogram[routeCount];
            for (int r = 0; r < routeCount; r++) {
                resolutions[r] = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);
            }

            for (Node node : nodes) {
                if (node.parent != -1) {
                    continue;
                }
                int r = node.route;
                // Each route is scored against its own deadline: a root only counts if it had a
                // full deadline left in the window, and succeeds only if it resolved within it.
                if (node.rootArrivalMs > durationMs - routeDeadlines[r]) {
                    continue;
                }
                scored[r]++;
                boolean ok = node.state == STATE_DONE && !node.failed
                        && node.resolvedMs - node.rootArrivalMs <= routeDeadlines[r];
                int window = (int) (node.rootArrivalMs / windowMs);
                if (ok) {
                    success[r]++;
                }
                if (window < windowCount) {
                    windowOffered[r][window]++;
                    if (ok) {
                        windowSuccess[r][window]++;
                    }
                }
                if (node.state == STATE_DONE) {
                    resolutions[r].recordValue(Math.clamp(
                            Math.round(node.resolvedMs - node.rootArrivalMs), 0L, MAX_LATENCY_MS));
                }
            }

            List<RouteResult> routeResults = new ArrayList<>(routeCount);
            for (int r = 0; r < routeCount; r++) {
                List<Double> perWindow = new ArrayList<>(windowCount);
                for (int w = 0; w < windowCount; w++) {
                    perWindow.add(windowOffered[r][w] == 0L
                            ? 100.0 : 100.0 * windowSuccess[r][w] / windowOffered[r][w]);
                }
                routeResults.add(new RouteResult(
                        routes.get(r).name(),
                        scored[r] == 0L ? 100.0 : 100.0 * success[r] / scored[r],
                        resolutions[r].getValueAtPercentile(50.0),
                        resolutions[r].getValueAtPercentile(99.0),
                        List.copyOf(perWindow)));
            }

            Map<String, Long> attempts = new LinkedHashMap<>();
            Map<String, List<Integer>> attemptsPerWindow = new LinkedHashMap<>();
            for (String service : states.keySet()) {
                attempts.put(service, spawnTotals.get(service)[0]);
                List<Integer> counts = new ArrayList<>(windowCount);
                for (int w = 0; w < windowCount; w++) {
                    counts.add(windowSpawns.get(service)[w]);
                }
                attemptsPerWindow.put(service, List.copyOf(counts));
            }

            List<Integer> perRoot = new ArrayList<>(rootCount);
            for (int r = 0; r < rootCount; r++) {
                perRoot.add(0);
            }
            for (int rootIndex : leafAttempts) {
                perRoot.set(rootIndex, perRoot.get(rootIndex) + 1);
            }

            Map<String, List<CircuitBreaker.Transition>> transitionLog = new LinkedHashMap<>();
            Map<String, List<Integer>> stateLog = new LinkedHashMap<>();
            for (Map.Entry<String, EdgeBreaker> entry : breakers.entrySet()) {
                transitionLog.put(entry.getKey(), List.copyOf(entry.getValue().transitions()));
                stateLog.put(entry.getKey(), stateAtWindowStarts(entry.getValue()));
            }

            return new BreakerOutcome(
                    List.copyOf(routeResults),
                    attempts,
                    attemptsPerWindow,
                    List.copyOf(perRoot),
                    leafSpawnsPastDeadline,
                    transitionLog,
                    stateLog);
        }

        /** Breaker state at each window start, reconstructed from the transition log. */
        private List<Integer> stateAtWindowStarts(EdgeBreaker breaker) {
            List<CircuitBreaker.Transition> transitions = breaker.transitions();
            List<Integer> result = new ArrayList<>(windowCount);
            int next = 0;
            CircuitBreaker.State current = CircuitBreaker.State.CLOSED;
            for (int w = 0; w < windowCount; w++) {
                double windowStart = (double) w * windowMs;
                while (next < transitions.size() && transitions.get(next).timeMs() < windowStart) {
                    current = transitions.get(next).to();
                    next++;
                }
                result.add(current.ordinal());
            }
            return result;
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
            if (route.chain().size() < 2) {
                throw new IllegalArgumentException("chains must have at least two hops");
            }
            for (String service : route.chain()) {
                if (!services.containsKey(service)) {
                    throw new IllegalArgumentException(
                            "route " + route.name() + " references unknown service: " + service);
                }
            }
        }
    }
}
