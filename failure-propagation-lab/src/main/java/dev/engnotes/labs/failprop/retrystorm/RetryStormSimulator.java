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
package dev.engnotes.labs.failprop.retrystorm;

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
 * Deterministic discrete-event model of a service chain whose non-leaf hops apply a per-call
 * timeout-and-retry policy to their downstream calls, used to demonstrate retry amplification
 * for the retry-storms experiment.
 *
 * <p>Every downstream call attempt is its own node in a call tree. A caller waits up to
 * {@link RetryPolicy#perCallTimeoutMs()} per attempt; on timeout <em>or</em> a failure
 * response it retries after {@link RetryPolicy#backoffMs()}, up to
 * {@link RetryPolicy#attemptsPerHop()} attempts, then fails upward. Two modelling choices
 * carry the lesson:
 * <ul>
 *   <li><strong>Abandoned work keeps running.</strong> A timed-out attempt is only
 *       <em>abandoned</em>: the caller stops listening, but the attempt's subtree continues -
 *       queueing, consuming service slots, and launching its own remaining retries. The
 *       {@code abandoned} flag gates exactly one thing: the upward notification. This is why
 *       R attempts at each of d hops becomes up to R^d attempts at the leaf.</li>
 *   <li><strong>The client does not retry.</strong> Client-side retry amplification on a
 *       single server is Series 2 Post 1's story; this experiment isolates what the service
 *       mesh does internally on top of one client request.</li>
 * </ul>
 *
 * <p>Success is scored at the root: a request succeeds if its chain completed within
 * {@code deadlineMs} of arrival. Only roots with a full deadline left in-window are scored
 * (the Series 2 Post 5 cutoff). The reported p99 is time-to-<em>resolution</em> (success or
 * give-up) over scored roots that resolved inside the run.
 *
 * <p>Fully synthetic and single-threaded for byte-stable golden output (ADR-007): every event
 * carries a monotonic sequence id, and attempt timeouts carry a per-node generation stamp so a
 * timeout that lost the race against a fast response can never fire a second retry.
 */
public final class RetryStormSimulator {

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
    private final RetryPolicy policy;

    public RetryStormSimulator(List<ServiceConfig> services, RetryPolicy policy) {
        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("services must not be empty");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        Map<String, ServiceConfig> byName = new LinkedHashMap<>();
        for (ServiceConfig service : services) {
            if (byName.putIfAbsent(service.name(), service) != null) {
                throw new IllegalArgumentException("duplicate service name: " + service.name());
            }
        }
        this.services = byName;
        this.policy = policy;
    }

    /**
     * Full run outcome. Attempt counts are spawn totals over the whole run window;
     * {@code leafAttemptsPerRoot} counts leaf-service attempts attributed to each root in
     * arrival order, so callers can compute amplification over roots whose retry tree had
     * room to finish inside the window.
     */
    public record StormOutcome(
            double successPct,
            double p99ResolutionMs,
            long totalRoots,
            Map<String, Long> attemptsByService,
            List<Double> windowSuccessPct,
            Map<String, List<Integer>> windowAttemptsByService,
            List<Integer> leafAttemptsPerRoot) {}

    /**
     * Runs the route's demand through the chain under this simulator's retry policy.
     *
     * @param route      client demand; the chain's non-leaf hops apply the retry policy
     * @param durationMs run window; must exceed {@code deadlineMs} so a scoring window exists
     * @param deadlineMs client deadline a root must beat to count as a success
     * @param windowMs   per-window resolution for success rates and attempt counts
     */
    public StormOutcome run(RouteDemand route, long durationMs, long deadlineMs, long windowMs) {
        validate(route, durationMs, deadlineMs, windowMs);

        long scoringCutoffMs = durationMs - deadlineMs;
        int windowCount = (int) (scoringCutoffMs / windowMs);
        List<String> chain = route.chain();

        Sim sim = new Sim(chain, windowMs, windowCount);

        // Root arrivals: deterministic, evenly spaced, no client retry.
        double interMs = 1000.0 / route.rateRps();
        for (long k = 0; ; k++) {
            double t = k * interMs;
            if (t >= durationMs) {
                break;
            }
            int root = sim.spawn(0, -1, t);
            sim.nodes.get(root).rootArrivalMs = t;
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

        return sim.score(deadlineMs, scoringCutoffMs);
    }

    // -------------------------------------------------------------------------
    // Private machinery
    // -------------------------------------------------------------------------

    private record Event(double timeMs, long seq, int kind, int node, int stamp) {}

    private static final class Node {
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

        Node(int hop, int parent, int rootIndex) {
            this.hop = hop;
            this.parent = parent;
            this.rootIndex = rootIndex;
        }
    }

    private static final class ServiceState {
        final ServiceConfig config;
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        int busy;

        ServiceState(ServiceConfig config) {
            this.config = config;
        }
    }

    /** Mutable run state; one instance per {@link #run} call so the simulator stays reusable. */
    private final class Sim {
        final List<String> chain;
        final long windowMs;
        final int windowCount;
        final List<Node> nodes = new ArrayList<>();
        final PriorityQueue<Event> events = new PriorityQueue<>(
                Comparator.comparingDouble(Event::timeMs).thenComparingLong(Event::seq));
        final Map<String, ServiceState> states = new LinkedHashMap<>();
        final Map<String, long[]> spawnTotals = new LinkedHashMap<>();
        final Map<String, int[]> windowSpawns = new LinkedHashMap<>();
        final List<Integer> leafAttempts = new ArrayList<>(); // root index per leaf spawn
        int rootCount;
        long seq;

        Sim(List<String> chain, long windowMs, int windowCount) {
            this.chain = chain;
            this.windowMs = windowMs;
            this.windowCount = windowCount;
            for (ServiceConfig config : services.values()) {
                states.put(config.name(), new ServiceState(config));
                spawnTotals.put(config.name(), new long[1]);
                windowSpawns.put(config.name(), new int[windowCount]);
            }
        }

        int spawn(int hop, int parent, double timeMs) {
            int rootIndex = parent == -1 ? rootCount++ : nodes.get(parent).rootIndex;
            Node node = new Node(hop, parent, rootIndex);
            nodes.add(node);
            int index = nodes.size() - 1;
            String service = chain.get(hop);
            spawnTotals.get(service)[0]++;
            if (hop == chain.size() - 1) {
                leafAttempts.add(rootIndex);
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
            ServiceState state = states.get(chain.get(node.hop));
            if (state.busy < state.config.workers()) {
                startWork(state, index, timeMs);
            } else {
                state.queue.addLast(index);
            }
        }

        void startWork(ServiceState state, int index, double timeMs) {
            state.busy++;
            if (state.busy > state.config.workers()) {
                throw new IllegalStateException("worker pool over-committed at " + state.config.name());
            }
            nodes.get(index).state = STATE_WORKING;
            events.add(new Event(timeMs + state.config.ownWork().at(timeMs),
                    seq++, KIND_WORK_DONE, index, 0));
        }

        void onWorkDone(int index, double timeMs) {
            Node node = nodes.get(index);
            if (node.hop == chain.size() - 1) {
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
            node.activeChild = spawn(node.hop + 1, index, timeMs);
            events.add(new Event(timeMs + policy.perCallTimeoutMs(),
                    seq++, KIND_CHILD_TIMEOUT, index, node.generation));
        }

        void onChildTimeout(int index, int generation, double timeMs) {
            Node node = nodes.get(index);
            // Generation stamp: a timeout that lost the race against a fast response (the
            // response already advanced the generation) must never fire a second retry.
            if (node.state != STATE_WAITING || node.generation != generation) {
                return;
            }
            nodes.get(node.activeChild).abandoned = true;
            node.activeChild = -1;
            node.generation++;
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
            node.state = STATE_DONE;
            node.failed = !ok;
            node.resolvedMs = timeMs;
            release(states.get(chain.get(node.hop)), timeMs);
            if (node.parent >= 0 && !node.abandoned) {
                parentReact(node.parent, ok, timeMs);
            }
        }

        void parentReact(int index, boolean childOk, double timeMs) {
            Node node = nodes.get(index);
            if (node.state != STATE_WAITING) {
                throw new IllegalStateException("response delivered to a node that is not waiting");
            }
            if (!childOk) {
                // Under a uniform per-call timeout this is unreachable: a child can only fail
                // by exhausting its own retry budget, which always outlasts its parent's
                // timeout by the child's own-work margin - so the parent has already abandoned
                // it and the notify is skipped. Asserted so that adding per-hop policies later
                // trips loudly here instead of silently double-counting retries.
                throw new IllegalStateException(
                        "fail-response is unreachable under a uniform per-call timeout");
            }
            node.activeChild = -1;
            node.generation++; // invalidate the pending timeout for this attempt
            finish(index, true, timeMs);
        }

        void release(ServiceState state, double timeMs) {
            state.busy--;
            if (state.busy < 0) {
                throw new IllegalStateException("worker pool under-committed at " + state.config.name());
            }
            Integer next = state.queue.pollFirst();
            if (next != null) {
                startWork(state, next, timeMs);
            }
        }

        StormOutcome score(long deadlineMs, long scoringCutoffMs) {
            long totalRoots = 0L;
            long scored = 0L;
            long success = 0L;
            long[] windowOffered = new long[windowCount];
            long[] windowSuccess = new long[windowCount];
            Histogram resolutions = new Histogram(MAX_LATENCY_MS, SIGNIFICANT_DIGITS);

            for (Node node : nodes) {
                if (node.parent != -1) {
                    continue;
                }
                totalRoots++;
                if (node.rootArrivalMs > scoringCutoffMs) {
                    continue;
                }
                scored++;
                boolean ok = node.state == STATE_DONE && !node.failed
                        && node.resolvedMs - node.rootArrivalMs <= deadlineMs;
                int window = (int) (node.rootArrivalMs / windowMs);
                if (ok) {
                    success++;
                }
                if (window < windowCount) {
                    windowOffered[window]++;
                    if (ok) {
                        windowSuccess[window]++;
                    }
                }
                if (node.state == STATE_DONE) {
                    resolutions.recordValue(Math.clamp(
                            Math.round(node.resolvedMs - node.rootArrivalMs), 0L, MAX_LATENCY_MS));
                }
            }

            List<Double> perWindow = new ArrayList<>(windowCount);
            for (int w = 0; w < windowCount; w++) {
                perWindow.add(windowOffered[w] == 0L ? 100.0 : 100.0 * windowSuccess[w] / windowOffered[w]);
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

            return new StormOutcome(
                    scored == 0L ? 100.0 : 100.0 * success / scored,
                    resolutions.getValueAtPercentile(99.0),
                    totalRoots,
                    attempts,
                    List.copyOf(perWindow),
                    attemptsPerWindow,
                    List.copyOf(perRoot));
        }
    }

    private void validate(RouteDemand route, long durationMs, long deadlineMs, long windowMs) {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        if (deadlineMs <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("deadlineMs and windowMs must be > 0");
        }
        if (durationMs <= deadlineMs) {
            throw new IllegalArgumentException(
                    "durationMs must exceed deadlineMs: the scoring window (duration - deadline) is empty");
        }
        if (route.chain().size() < 2) {
            throw new IllegalArgumentException("chain must have at least two hops for retries to exist");
        }
        for (String service : route.chain()) {
            if (!services.containsKey(service)) {
                throw new IllegalArgumentException(
                        "route " + route.name() + " references unknown service: " + service);
            }
        }
    }
}
