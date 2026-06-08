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

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.failprop.cascade.RouteDemand;
import dev.engnotes.labs.failprop.cascade.ServiceConfig;
import dev.engnotes.labs.failprop.cascade.ServiceTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two circuit-breaker experiments.
 *
 * <p><strong>Experiment 1 (hard-down comparison)</strong> replays Post 2's exact scenario - the
 * single over-provisioned chain, R=3, database hard-down at 500ms - with and without breakers.
 * The naive row reproduces Post 2's golden numbers exactly (the cross-check); the breakered row
 * shows what the breaker buys against a real outage: the R^2 storm collapses to a probe
 * trickle, and the median failure goes from a 1305ms hang to a fast fail. What it cannot buy is
 * successes - nothing rescues a hard-down dependency.
 *
 * <p><strong>Experiment 2 (blast-radius timeline)</strong> brings back Post 1's topology: a
 * <em>constrained</em> shared frontend (pool 20) serving route-a (which depends on the
 * database) and route-b (which doesn't). Post 2's retry policy turns a transient database
 * degradation into 1305ms worker holds, exhausting the shared pool and killing route-b - Post
 * 1's cascade, amplified by Post 2's retries. The breakered run fails fast instead: workers
 * release in ~150ms, route-b barely notices, and the database sees probes instead of a storm.
 * The price is paid by route-a's recovery: breakers re-close bottom-up, one probe cycle per
 * edge, so route-a returns later than the naive run. Protection of the many, paid by the few.
 *
 * <p>Breakers sit on every client edge (uniform deployment, like the retry policy itself):
 * {@code frontend->service-a}, {@code service-a->database}, and {@code frontend->service-b}
 * (which never trips - a breaker on a healthy edge costs nothing).
 */
public final class BreakerStormScenario {

    // Post 2's chain constants (the sweep cross-check depends on these matching exactly).
    static final int WIDE_POOL = 200;
    static final long FRONTEND_WORK_MS = 5L;
    static final long SERVICE_A_WORK_MS = 5L;
    static final int DB_WORKERS = 10;
    static final long DB_HEALTHY_MS = 10L;
    static final long DB_DEGRADED_MS = 500L;

    // Post 1's blast-radius constants for the timeline.
    static final int SHARED_FRONTEND_WORKERS = 20;
    static final int SERVICE_B_WORKERS = 10;
    static final long SERVICE_B_WORK_MS = 10L;
    static final double DEGRADE_FROM_MS = 1_500.0;
    static final double DEGRADE_TO_MS = 2_500.0;

    static final double ROUTE_RATE_RPS = 50.0;
    /** Sweep deadline: Post 2's value, so the naive row reproduces Post 2's golden exactly. */
    static final long CLIENT_DEADLINE_MS = 1_000L;
    /**
     * Blast-timeline deadline: route-b is an interactive route with a 300ms budget. A 1000ms
     * deadline would forgive almost a full second of shared-pool queueing inside the 1s blip
     * and hide the blast radius entirely - clients do not wait around to be that forgiving.
     */
    static final long BLAST_DEADLINE_MS = 300L;
    static final long WINDOW_MS = 100L;

    static final BreakerStormSimulator.RetryPolicyView RETRY_POLICY =
            new BreakerStormSimulator.RetryPolicyView(3, 400L, 50L);

    /** 50% failures over the last 20 calls (min 10), 500ms open, single probe. */
    static final BreakerConfig BREAKER_CONFIG = new BreakerConfig(50.0, 20, 10, 500L, 1);

    static final String EDGE_FRONTEND_A = "frontend->service-a";
    static final String EDGE_FRONTEND_B = "frontend->service-b";
    static final String EDGE_A_DATABASE = "service-a->database";

    /**
     * Runs both experiments for the window length in {@code args}.
     */
    public BreakerRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());

        List<BreakerSweepPoint> sweep = new ArrayList<>(2);
        sweep.add(sweepPoint("naive-retry", Map.of(), durationMs));
        sweep.add(sweepPoint("with-breaker", chainBreakers(), durationMs));
        sweep.add(sweepPoint("resilience4j", r4jChainBreakers(), durationMs));

        BreakerStormSimulator.BreakerOutcome naive = new BreakerStormSimulator(
                blastTopology(), RETRY_POLICY, Map.of())
                .run(blastRoutes(), durationMs, BLAST_DEADLINE_MS, WINDOW_MS);
        BreakerStormSimulator.BreakerOutcome breakered = new BreakerStormSimulator(
                blastTopology(), RETRY_POLICY, blastBreakers())
                .run(blastRoutes(), durationMs, BLAST_DEADLINE_MS, WINDOW_MS);

        return new BreakerRunResult(sweep, toTimeline(naive, breakered));
    }

    // -------------------------------------------------------------------------
    // Experiment 1: hard-down comparison on Post 2's chain
    // -------------------------------------------------------------------------

    static List<ServiceConfig> chainTopology(ServiceTime dbServiceTime) {
        return List.of(
                new ServiceConfig("frontend", WIDE_POOL, ServiceTime.constant(FRONTEND_WORK_MS)),
                new ServiceConfig("service-a", WIDE_POOL, ServiceTime.constant(SERVICE_A_WORK_MS)),
                new ServiceConfig("database", DB_WORKERS, dbServiceTime));
    }

    static RouteDemand chainRoute() {
        return new RouteDemand("checkout", List.of("frontend", "service-a", "database"), ROUTE_RATE_RPS);
    }

    static Map<String, EdgeBreaker> chainBreakers() {
        Map<String, EdgeBreaker> breakers = new LinkedHashMap<>();
        breakers.put(EDGE_FRONTEND_A, new CircuitBreaker(BREAKER_CONFIG));
        breakers.put(EDGE_A_DATABASE, new CircuitBreaker(BREAKER_CONFIG));
        return breakers;
    }

    private static BreakerSweepPoint sweepPoint(
            String policy, Map<String, EdgeBreaker> breakers, long durationMs) {
        BreakerStormSimulator.BreakerOutcome outcome = new BreakerStormSimulator(
                chainTopology(ServiceTime.constant(DB_DEGRADED_MS)), RETRY_POLICY, breakers)
                .run(List.of(chainRoute()), durationMs, CLIENT_DEADLINE_MS, WINDOW_MS);

        BreakerStormSimulator.RouteResult route = outcome.routes().get(0);
        long dbAttempts = outcome.attemptsByService().get("database");
        return new BreakerSweepPoint(
                policy,
                route.successPct(),
                attemptsPerEligibleRoot(outcome, durationMs),
                dbAttempts / (durationMs / 1000.0),
                route.p50ResolutionMs(),
                route.p99ResolutionMs());
    }

    /** Post 2's eligible-root amplification metric, identical budget arithmetic. */
    private static double attemptsPerEligibleRoot(
            BreakerStormSimulator.BreakerOutcome outcome, long durationMs) {
        long treeBudgetMs = FRONTEND_WORK_MS + SERVICE_A_WORK_MS
                + 2L * (RETRY_POLICY.attemptsPerHop() - 1)
                * (RETRY_POLICY.perCallTimeoutMs() + RETRY_POLICY.backoffMs());
        double interMs = 1000.0 / ROUTE_RATE_RPS;
        int eligible = (int) Math.floor((durationMs - treeBudgetMs) / interMs) + 1;
        List<Integer> perRoot = outcome.leafAttemptsPerRoot();
        eligible = Math.clamp(eligible, 1, perRoot.size());

        long total = 0L;
        for (int r = 0; r < eligible; r++) {
            total += perRoot.get(r);
        }
        return (double) total / eligible;
    }

    // -------------------------------------------------------------------------
    // Experiment 2: blast-radius timeline on Post 1's topology
    // -------------------------------------------------------------------------

    static List<ServiceConfig> blastTopology() {
        return List.of(
                new ServiceConfig("frontend", SHARED_FRONTEND_WORKERS, ServiceTime.constant(FRONTEND_WORK_MS)),
                new ServiceConfig("service-a", WIDE_POOL, ServiceTime.constant(SERVICE_A_WORK_MS)),
                new ServiceConfig("service-b", SERVICE_B_WORKERS, ServiceTime.constant(SERVICE_B_WORK_MS)),
                new ServiceConfig("database", DB_WORKERS, ServiceTime.degradedBetween(
                        DEGRADE_FROM_MS, DEGRADE_TO_MS, DB_HEALTHY_MS, DB_DEGRADED_MS)));
    }

    static List<RouteDemand> blastRoutes() {
        return List.of(
                new RouteDemand("route-a", List.of("frontend", "service-a", "database"), ROUTE_RATE_RPS),
                new RouteDemand("route-b", List.of("frontend", "service-b"), ROUTE_RATE_RPS));
    }

    /** The same edges guarded by the real Resilience4j breaker through the synthetic-clock adapter. */
    static Map<String, EdgeBreaker> r4jChainBreakers() {
        Map<String, EdgeBreaker> breakers = new LinkedHashMap<>();
        breakers.put(EDGE_FRONTEND_A, new Resilience4jBreakerAdapter("frontend-a", BREAKER_CONFIG));
        breakers.put(EDGE_A_DATABASE, new Resilience4jBreakerAdapter("a-database", BREAKER_CONFIG));
        return breakers;
    }

    static Map<String, EdgeBreaker> blastBreakers() {
        Map<String, EdgeBreaker> breakers = new LinkedHashMap<>();
        breakers.put(EDGE_FRONTEND_A, new CircuitBreaker(BREAKER_CONFIG));
        breakers.put(EDGE_FRONTEND_B, new CircuitBreaker(BREAKER_CONFIG));
        breakers.put(EDGE_A_DATABASE, new CircuitBreaker(BREAKER_CONFIG));
        return breakers;
    }

    private static List<BreakerWindowSample> toTimeline(
            BreakerStormSimulator.BreakerOutcome naive, BreakerStormSimulator.BreakerOutcome breakered) {
        BreakerStormSimulator.RouteResult naiveA = naive.routes().get(0);
        BreakerStormSimulator.RouteResult naiveB = naive.routes().get(1);
        BreakerStormSimulator.RouteResult breakerA = breakered.routes().get(0);
        BreakerStormSimulator.RouteResult breakerB = breakered.routes().get(1);
        List<Integer> naiveDb = naive.windowAttemptsByService().get("database");
        List<Integer> breakerDb = breakered.windowAttemptsByService().get("database");
        List<Integer> frontendEdge = breakered.windowBreakerState().get(EDGE_FRONTEND_A);
        List<Integer> databaseEdge = breakered.windowBreakerState().get(EDGE_A_DATABASE);
        double windowsPerSecond = 1000.0 / WINDOW_MS;

        int windows = naiveA.windowSuccessPct().size();
        List<BreakerWindowSample> timeline = new ArrayList<>(windows);
        for (int w = 0; w < windows; w++) {
            timeline.add(new BreakerWindowSample(
                    (long) w * WINDOW_MS,
                    naiveA.windowSuccessPct().get(w),
                    naiveB.windowSuccessPct().get(w),
                    breakerA.windowSuccessPct().get(w),
                    breakerB.windowSuccessPct().get(w),
                    naiveDb.get(w) * windowsPerSecond,
                    breakerDb.get(w) * windowsPerSecond,
                    frontendEdge.get(w),
                    databaseEdge.get(w)));
        }
        return timeline;
    }
}
