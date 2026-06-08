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

import dev.engnotes.labs.failprop.breaker.BreakerStormSimulator;
import dev.engnotes.labs.failprop.breaker.TimeoutBudget;
import dev.engnotes.labs.failprop.cascade.RouteDemand;
import dev.engnotes.labs.failprop.cascade.ServiceConfig;
import dev.engnotes.labs.failprop.cascade.ServiceTime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a propagated deadline buys: it caps every request's latency at the deadline, never
 * starts work past it, and - tight enough to admit one attempt - prevents the retry storm so the
 * budget alone beats the breaker. The hard-down budget-off cross-check ties the new gate back to
 * Post 2's golden, proving the gate is genuinely inert when disabled.
 */
class BudgetScenarioTest {

    private static final long DURATION_MS = 5_000L;
    private static final long WINDOW_MS = BudgetScenario.WINDOW_MS;

    private static BreakerStormSimulator.BreakerOutcome run(boolean breaker, boolean budgeted, long deadlineMs) {
        return BudgetScenario.simulate(breaker, budgeted, deadlineMs, DURATION_MS);
    }

    @Test
    void uncoordinatedTimeoutsLeaveWorkRunningPastTheDeadline() {
        BreakerStormSimulator.BreakerOutcome outcome = run(false, false, 1_000L);
        assertTrue(outcome.leafSpawnsPastDeadline() > 0,
                "without a budget, abandoned retry chains keep spawning past the deadline");
        assertEquals(1305.0, outcome.routes().get(0).p99ResolutionMs(), 0.01,
                "p99 is the full uncoordinated retry budget, not the client deadline");
    }

    @Test
    void aPropagatedDeadlineStartsNoWorkPastIt() {
        // The defining property: with budgeting, no leaf attempt ever begins after the deadline.
        for (long deadlineMs : new long[] {400L, 600L, 1_000L}) {
            BreakerStormSimulator.BreakerOutcome outcome = run(false, true, deadlineMs);
            assertEquals(0L, outcome.leafSpawnsPastDeadline(),
                    "budgeted run must start no work past the " + deadlineMs + "ms deadline");
        }
    }

    @Test
    void theBudgetCapsP99AtTheDeadline() {
        // p99 tracks the deadline (the dial); without it, p99 is flat regardless of the deadline.
        assertEquals(450.0, run(false, true, 400L).routes().get(0).p99ResolutionMs(), 0.01);
        assertEquals(650.0, run(false, true, 600L).routes().get(0).p99ResolutionMs(), 0.01);
        assertEquals(1000.0, run(false, true, 1_000L).routes().get(0).p99ResolutionMs(), 0.01);
        // The breaker does not cap a single request's latency - it bounds load, not latency.
        assertEquals(905.0, run(true, false, 400L).routes().get(0).p99ResolutionMs(), 0.01);
        assertEquals(905.0, run(true, false, 1_000L).routes().get(0).p99ResolutionMs(), 0.01);
    }

    @Test
    void aTightDeadlinePreventsTheStormAndBeatsTheBreaker() {
        // Below one retry-width (timeout 400 + backoff 50 = 450ms) only a single attempt fits,
        // so no storm forms, the database stays unsaturated, and the healthy majority succeeds -
        // immediately, with no warmup the breaker would need.
        BreakerStormSimulator.BreakerOutcome budget = run(false, true, 400L);
        BreakerStormSimulator.BreakerOutcome breaker = run(true, false, 400L);
        assertTrue(budget.routes().get(0).successPct() > breaker.routes().get(0).successPct() + 10.0,
                "tight-deadline budget beats the breaker: "
                        + budget.routes().get(0).successPct() + " vs " + breaker.routes().get(0).successPct());
        assertTrue(budget.attemptsByService().get("database")
                        < breaker.attemptsByService().get("database") * 2,
                "the storm never forms, so database load stays low without the breaker");
    }

    @Test
    void aLooseDeadlineLetsTheStormFormSoTheBreakerWins() {
        // Above one retry-width the storm forms despite budgeting; now the breaker's load relief
        // is the stronger tool. Budget and breaker are complementary, not ranked.
        BreakerStormSimulator.BreakerOutcome budget = run(false, true, 1_000L);
        BreakerStormSimulator.BreakerOutcome breaker = run(true, false, 1_000L);
        assertTrue(breaker.routes().get(0).successPct() > budget.routes().get(0).successPct() + 10.0,
                "loose-deadline breaker beats the budget alone");
    }

    @Test
    void hardDownBudgetOffReproducesPostTwo() {
        // The gate is genuinely inert when off: against a hard-down database with no budget, the
        // simulator reproduces Post 2's golden amplification (9 db attempts, 1305ms give-up).
        List<ServiceConfig> hardDown = List.of(
                new ServiceConfig("frontend", 200, ServiceTime.constant(5L)),
                new ServiceConfig("service-a", 200, ServiceTime.constant(5L)),
                new ServiceConfig("database", 10, ServiceTime.constant(500L)));
        BreakerStormSimulator.BreakerOutcome outcome = new BreakerStormSimulator(
                hardDown, BudgetScenario.RETRY_POLICY, Map.of(), null)
                .run(List.of(BudgetScenario.route()), DURATION_MS, 1_000L, WINDOW_MS);
        assertEquals(9, outcome.leafAttemptsPerRoot().get(0), "R^2 amplification, Post 2's value");
        assertEquals(1305.0, outcome.routes().get(0).p99ResolutionMs(), 0.01);
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(run(true, true, 400L), run(true, true, 400L),
                "identical inputs must produce identical results");
    }
}
