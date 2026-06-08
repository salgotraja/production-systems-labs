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
package dev.engnotes.labs.failprop.bulkhead;

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.failprop.breaker.BreakerConfig;
import dev.engnotes.labs.failprop.breaker.BreakerStormSimulator;
import dev.engnotes.labs.failprop.breaker.CircuitBreaker;
import dev.engnotes.labs.failprop.breaker.EdgeBreaker;
import dev.engnotes.labs.failprop.breaker.TimeoutBudget;
import dev.engnotes.labs.failprop.cascade.RouteDemand;
import dev.engnotes.labs.failprop.cascade.ServiceConfig;
import dev.engnotes.labs.failprop.cascade.ServiceTime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The failure-isolation (bulkhead) experiments - the Series 3 capstone.
 *
 * <p>The failure mode is the one Post 1 diagnosed and the later tools cannot touch: a
 * <em>slow-but-healthy</em> neighbour hogging a shared resource. route-a is a slow batch
 * endpoint (a database call just under the per-call timeout, so it succeeds in ~390ms) on a
 * loose 1000ms SLA; route-b is a fast interactive endpoint (~15ms) on a tight 100ms SLA. They
 * share a 20-worker frontend pool. route-a's offered concurrency exceeds the pool, so it
 * saturates the shared queue, and route-b - which never touches route-a's database - starves
 * behind 390ms holds.
 *
 * <p>Nothing fails, so there is nothing for the detection-based tools to act on:
 * <ul>
 *   <li>the <strong>circuit breaker</strong> (Post 3) never sees a failure - every call
 *       succeeds, slowly - so it stays closed and inert;</li>
 *   <li>the <strong>timeout budget</strong> (Post 4) gates downstream calls, but route-b's loss
 *       is the frontend <em>queue</em> wait, which happens before any downstream call - so the
 *       budget is blind to it too.</li>
 * </ul>
 * Only structural partitioning helps: a <strong>bulkhead</strong> that reserves a dedicated
 * slice of the frontend pool for route-b, so route-a can exhaust only its own partition. That
 * retro-justifies Post 1: the cascade is resource coupling, not failure propagation, so the fix
 * isolates the resource rather than detecting the failure.
 *
 * <p>Experiment 1 is the head-to-head: naive, breaker, budget, bulkhead, and all combined.
 * Experiment 2 is the bulkhead sizing sweep - the cost of isolation is forgone borrowing, so
 * reserve only what the protected route needs (Little's Law: {@code λ_b × sojourn_b ≈ 1 worker});
 * over-reserve and the noisy neighbour, denied the idle slack it could have borrowed, starves.
 */
public final class BulkheadScenario {

    static final int FRONTEND_POOL = 20;
    static final long FRONTEND_WORK_MS = 5L;
    static final long SERVICE_A_WORK_MS = 5L;
    static final int SERVICE_B_WORKERS = 200;
    static final long SERVICE_B_WORK_MS = 10L;
    static final int DB_WORKERS = 30;
    static final long DB_SLOW_MS = 380L; // just under the 400ms timeout: slow, but it succeeds

    static final double ROUTE_A_RPS = 55.0; // offered concurrency exceeds the pool -> it hogs
    static final double ROUTE_B_RPS = 50.0;
    static final long ROUTE_A_DEADLINE_MS = 1_000L; // slow batch SLA
    static final long ROUTE_B_DEADLINE_MS = 100L;   // fast interactive SLA
    static final long WINDOW_MS = 100L;

    /** route-b's correctly-sized reserve for the head-to-head: its Little's-Law need, rounded up. */
    static final int ROUTE_B_RESERVE = 1;

    static final BreakerStormSimulator.RetryPolicyView RETRY_POLICY =
            new BreakerStormSimulator.RetryPolicyView(3, 400L, 50L);
    static final BreakerConfig BREAKER_CONFIG = new BreakerConfig(50.0, 20, 10, 500L, 1);
    static final TimeoutBudget BUDGET = new TimeoutBudget(ROUTE_A_DEADLINE_MS, 15L);

    static final String EDGE_FRONTEND_A = "frontend->service-a";
    static final String EDGE_A_DATABASE = "service-a->database";

    /**
     * Runs both experiments. The run window comes from {@code --duration}; the per-route
     * deadlines and the topology are fixed.
     */
    public BulkheadRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());

        List<BulkheadPolicyPoint> policies = new ArrayList<>(5);
        policies.add(policyPoint("naive", false, false, false, durationMs));
        policies.add(policyPoint("breaker", true, false, false, durationMs));
        policies.add(policyPoint("budget", false, true, false, durationMs));
        policies.add(policyPoint("bulkhead", false, false, true, durationMs));
        policies.add(policyPoint("all-three", true, true, true, durationMs));

        List<BulkheadSweepPoint> sizing = new ArrayList<>();
        for (int reserve = 1; reserve <= 8; reserve++) {
            sizing.add(sizingPoint(reserve, durationMs));
        }

        return new BulkheadRunResult(policies, sizing);
    }

    // -------------------------------------------------------------------------
    // Topology
    // -------------------------------------------------------------------------

    static List<ServiceConfig> topology() {
        return List.of(
                new ServiceConfig("frontend", FRONTEND_POOL, ServiceTime.constant(FRONTEND_WORK_MS)),
                new ServiceConfig("service-a", 200, ServiceTime.constant(SERVICE_A_WORK_MS)),
                new ServiceConfig("service-b", SERVICE_B_WORKERS, ServiceTime.constant(SERVICE_B_WORK_MS)),
                new ServiceConfig("database", DB_WORKERS, ServiceTime.constant(DB_SLOW_MS)));
    }

    static List<RouteDemand> routes() {
        return List.of(
                new RouteDemand("route-a", List.of("frontend", "service-a", "database"), ROUTE_A_RPS),
                new RouteDemand("route-b", List.of("frontend", "service-b"), ROUTE_B_RPS));
    }

    static long[] deadlines() {
        return new long[] {ROUTE_A_DEADLINE_MS, ROUTE_B_DEADLINE_MS};
    }

    static Map<String, EdgeBreaker> breakers(boolean enabled) {
        if (!enabled) {
            return Map.of();
        }
        Map<String, EdgeBreaker> breakers = new LinkedHashMap<>();
        breakers.put(EDGE_FRONTEND_A, new CircuitBreaker(BREAKER_CONFIG));
        breakers.put(EDGE_A_DATABASE, new CircuitBreaker(BREAKER_CONFIG));
        return breakers;
    }

    /** route-a gets {@code FRONTEND_POOL - reserve}, route-b gets {@code reserve} dedicated workers. */
    static Map<String, int[]> bulkhead(int reserve) {
        return Map.of("frontend", new int[] {FRONTEND_POOL - reserve, reserve});
    }

    static BreakerStormSimulator.BreakerOutcome simulate(
            boolean breaker, boolean budgeted, Map<String, int[]> bulkhead, long durationMs) {
        return new BreakerStormSimulator(
                topology(), RETRY_POLICY, breakers(breaker), budgeted ? BUDGET : null, bulkhead)
                .run(routes(), durationMs, deadlines(), WINDOW_MS);
    }

    // -------------------------------------------------------------------------
    // Point builders
    // -------------------------------------------------------------------------

    private static BulkheadPolicyPoint policyPoint(
            String policy, boolean breaker, boolean budgeted, boolean bulkheaded, long durationMs) {
        Map<String, int[]> bulkhead = bulkheaded ? bulkhead(ROUTE_B_RESERVE) : Map.of();
        BreakerStormSimulator.BreakerOutcome outcome = simulate(breaker, budgeted, bulkhead, durationMs);
        BreakerStormSimulator.RouteResult a = outcome.routes().get(0);
        BreakerStormSimulator.RouteResult b = outcome.routes().get(1);
        return new BulkheadPolicyPoint(
                policy, a.successPct(), a.p99ResolutionMs(), b.successPct(), b.p99ResolutionMs());
    }

    private static BulkheadSweepPoint sizingPoint(int reserve, long durationMs) {
        BreakerStormSimulator.BreakerOutcome outcome =
                simulate(false, false, bulkhead(reserve), durationMs);
        return new BulkheadSweepPoint(
                reserve,
                FRONTEND_POOL - reserve,
                outcome.routes().get(0).successPct(),
                outcome.routes().get(1).successPct());
    }
}
