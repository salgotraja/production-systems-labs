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
package dev.engnotes.labs.failprop.budget;

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
 * The two timeout-budget experiments, both over a <em>partially</em> degraded dependency: during
 * the whole run a fixed fraction of database calls are slow (past the per-call timeout), the rest
 * healthy. This is the regime Post 3's circuit breaker handles least cleanly - the slow minority
 * still drives a retry storm, but the dependency is not down, so the question is how to bound the
 * damage without abandoning the healthy majority.
 *
 * <p>The retry policy and the breaker are Post 3's exactly (R=3, 400ms per-call timeout, 50ms
 * backoff; a 50%/20-call breaker). The new control is a propagated {@link TimeoutBudget}: a
 * deadline carried with the request so every hop refuses to start - or to keep running - a call
 * that cannot finish in time.
 *
 * <p><strong>Experiment 1 (the deadline sweep)</strong> sweeps the client deadline and runs three
 * policies - no protection, breaker only, budget only - at each. Two things emerge. The budget's
 * p99 tracks the deadline exactly (it is the latency dial); the breaker's does not (it bounds the
 * dependency's load, not any single request's latency). And below one retry-width
 * ({@code timeout + backoff = 450ms}) the budget admits only a single attempt, so the retry storm
 * never forms, the database stays unsaturated, and the healthy majority succeeds - a regime where
 * the budget alone beats the breaker, because it acts on the first request with no warmup.
 *
 * <p><strong>Experiment 2 (the tight-deadline table)</strong> fixes the deadline at that tight
 * value and compares all four combinations, showing that budget and breaker are complementary:
 * the breaker relieves load, the budget caps latency and prevents the storm, and the production
 * answer uses both.
 */
public final class BudgetScenario {

    static final int WIDE_POOL = 200;
    static final long FRONTEND_WORK_MS = 5L;
    static final long SERVICE_A_WORK_MS = 5L;
    static final int DB_WORKERS = 10;
    static final long DB_HEALTHY_MS = 10L;
    static final long DB_SLOW_MS = 500L;
    static final double SLOW_FRACTION = 0.4;
    static final long DEGRADATION_SEED = 7L;

    static final double ROUTE_RATE_RPS = 50.0;
    static final long WINDOW_MS = 100L;
    static final long BUDGET_FLOOR_MS = 15L;

    static final BreakerStormSimulator.RetryPolicyView RETRY_POLICY =
            new BreakerStormSimulator.RetryPolicyView(3, 400L, 50L);
    static final BreakerConfig BREAKER_CONFIG = new BreakerConfig(50.0, 20, 10, 500L, 1);

    static final String EDGE_FRONTEND_A = "frontend->service-a";
    static final String EDGE_A_DATABASE = "service-a->database";

    /** One retry-width is {@code timeout + backoff = 450ms}; the sweep straddles it. */
    private static final long[] DEADLINE_SWEEP_MS = {400, 450, 500, 600, 800, 1000};

    /** The tight deadline the head-to-head table fixes (admits a single attempt). */
    public static final long TIGHT_DEADLINE_MS = 400L;

    /**
     * Runs both experiments for the window length in {@code args}. The run window comes from
     * {@code --duration}; the client deadline is the swept variable, not {@code --duration}.
     */
    public BudgetRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());

        List<BudgetSweepPoint> sweep = new ArrayList<>(DEADLINE_SWEEP_MS.length * 3);
        for (long deadlineMs : DEADLINE_SWEEP_MS) {
            sweep.add(sweepPoint(deadlineMs, "no-protection", false, false, durationMs));
            sweep.add(sweepPoint(deadlineMs, "breaker", true, false, durationMs));
            sweep.add(sweepPoint(deadlineMs, "budget", false, true, durationMs));
        }

        List<BudgetPolicyPoint> table = new ArrayList<>(4);
        table.add(tablePoint("no-protection", false, false, durationMs));
        table.add(tablePoint("breaker", true, false, durationMs));
        table.add(tablePoint("budget", false, true, durationMs));
        table.add(tablePoint("budget+breaker", true, true, durationMs));

        return new BudgetRunResult(sweep, table);
    }

    // -------------------------------------------------------------------------
    // Topology
    // -------------------------------------------------------------------------

    /** The over-provisioned chain with a partially degraded database. */
    static List<ServiceConfig> topology() {
        return List.of(
                new ServiceConfig("frontend", WIDE_POOL, ServiceTime.constant(FRONTEND_WORK_MS)),
                new ServiceConfig("service-a", WIDE_POOL, ServiceTime.constant(SERVICE_A_WORK_MS)),
                new ServiceConfig("database", DB_WORKERS, ServiceTime.partialDegradation(
                        0.0, Double.MAX_VALUE, DB_HEALTHY_MS, DB_SLOW_MS, SLOW_FRACTION, DEGRADATION_SEED)));
    }

    static RouteDemand route() {
        return new RouteDemand("checkout", List.of("frontend", "service-a", "database"), ROUTE_RATE_RPS);
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

    static BreakerStormSimulator.BreakerOutcome simulate(
            boolean breaker, boolean budgeted, long deadlineMs, long durationMs) {
        TimeoutBudget budget = budgeted ? new TimeoutBudget(deadlineMs, BUDGET_FLOOR_MS) : null;
        return new BreakerStormSimulator(topology(), RETRY_POLICY, breakers(breaker), budget)
                .run(List.of(route()), durationMs, deadlineMs, WINDOW_MS);
    }

    // -------------------------------------------------------------------------
    // Point builders
    // -------------------------------------------------------------------------

    private static BudgetSweepPoint sweepPoint(
            long deadlineMs, String policy, boolean breaker, boolean budgeted, long durationMs) {
        BreakerStormSimulator.BreakerOutcome outcome = simulate(breaker, budgeted, deadlineMs, durationMs);
        BreakerStormSimulator.RouteResult route = outcome.routes().get(0);
        return new BudgetSweepPoint(
                deadlineMs,
                policy,
                route.successPct(),
                dbAttemptsPerRequest(outcome, durationMs),
                route.p50ResolutionMs(),
                route.p99ResolutionMs());
    }

    private static BudgetPolicyPoint tablePoint(
            String policy, boolean breaker, boolean budgeted, long durationMs) {
        BreakerStormSimulator.BreakerOutcome outcome = simulate(breaker, budgeted, TIGHT_DEADLINE_MS, durationMs);
        BreakerStormSimulator.RouteResult route = outcome.routes().get(0);
        long leafTotal = outcome.leafAttemptsPerRoot().stream().mapToLong(Integer::longValue).sum();
        double pastPct = leafTotal == 0L ? 0.0 : 100.0 * outcome.leafSpawnsPastDeadline() / leafTotal;
        return new BudgetPolicyPoint(
                policy,
                route.successPct(),
                dbAttemptsPerRequest(outcome, durationMs),
                pastPct,
                route.p50ResolutionMs(),
                route.p99ResolutionMs());
    }

    private static double dbAttemptsPerRequest(
            BreakerStormSimulator.BreakerOutcome outcome, long durationMs) {
        long requests = Math.round(ROUTE_RATE_RPS * durationMs / 1000.0);
        return requests == 0L ? 0.0 : (double) outcome.attemptsByService().get("database") / requests;
    }
}
