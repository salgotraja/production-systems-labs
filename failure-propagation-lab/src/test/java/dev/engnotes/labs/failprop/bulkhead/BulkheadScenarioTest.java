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

import dev.engnotes.labs.failprop.breaker.BreakerStormSimulator;
import dev.engnotes.labs.failprop.breaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the capstone: against a slow-but-healthy neighbour hogging a shared pool, the
 * detection-based tools are inert (the breaker never trips, the budget never fires - both
 * reproduce the naive numbers exactly), and only a bulkhead saves the victim. The sizing sweep
 * pins the cost of isolation - over-reserving starves the neighbour.
 */
class BulkheadScenarioTest {

    private static final long DURATION_MS = 5_000L;

    private static BreakerStormSimulator.BreakerOutcome run(
            boolean breaker, boolean budgeted, Map<String, int[]> bulkhead) {
        return BulkheadScenario.simulate(breaker, budgeted, bulkhead, DURATION_MS);
    }

    private static double routeA(BreakerStormSimulator.BreakerOutcome o) {
        return o.routes().get(0).successPct();
    }

    private static double routeB(BreakerStormSimulator.BreakerOutcome o) {
        return o.routes().get(1).successPct();
    }

    @Test
    void aSlowNeighbourStarvesTheVictimOnASharedPool() {
        BreakerStormSimulator.BreakerOutcome naive = run(false, false, Map.of());
        assertEquals(100.0, routeA(naive), 0.01, "route-a is slow but succeeds - nothing has failed");
        assertTrue(routeB(naive) < 30.0, "route-b starves behind route-a's pool holds: " + routeB(naive));
    }

    @Test
    void theBreakerIsInertBecauseNothingFails() {
        BreakerStormSimulator.BreakerOutcome naive = run(false, false, Map.of());
        BreakerStormSimulator.BreakerOutcome breakered = run(true, false, Map.of());
        assertEquals(routeB(naive), routeB(breakered), 0.001,
                "the breaker changes nothing - it never sees a failure to trip on");
        boolean anyTrip = breakered.breakerTransitions().values().stream()
                .flatMap(java.util.List::stream)
                .anyMatch(t -> t.to() == CircuitBreaker.State.OPEN);
        assertTrue(!anyTrip, "no breaker edge may open - there are no failures");
    }

    @Test
    void theBudgetIsInertBecauseTheLossIsQueueWaitNotCallLatency() {
        BreakerStormSimulator.BreakerOutcome naive = run(false, false, Map.of());
        BreakerStormSimulator.BreakerOutcome budgeted = run(false, true, Map.of());
        assertEquals(routeB(naive), routeB(budgeted), 0.001,
                "the budget gates downstream calls, but route-b dies in the frontend queue before any");
    }

    @Test
    void onlyTheBulkheadSavesTheVictim() {
        BreakerStormSimulator.BreakerOutcome bulkheaded =
                run(false, false, BulkheadScenario.bulkhead(BulkheadScenario.ROUTE_B_RESERVE));
        assertEquals(100.0, routeB(bulkheaded), 0.01, "a dedicated slice keeps route-b whole");
        assertEquals(100.0, routeA(bulkheaded), 0.01,
                "and at its correctly-sized reserve the bulkhead costs route-a nothing");
    }

    @Test
    void overReservingStarvesTheNeighbour() {
        // route-b's need is one worker; reserving more steals capacity route-a could have used.
        assertEquals(100.0, routeA(run(false, false, BulkheadScenario.bulkhead(1))), 0.01);
        double a4 = routeA(run(false, false, BulkheadScenario.bulkhead(4)));
        assertTrue(a4 < 60.0, "over-reserving 4 workers for route-b starves route-a: " + a4);
        // route-b stays whole across the sweep - it never needs more than its small share.
        assertEquals(100.0, routeB(run(false, false, BulkheadScenario.bulkhead(4))), 0.01);
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(run(true, true, BulkheadScenario.bulkhead(1)),
                run(true, true, BulkheadScenario.bulkhead(1)),
                "identical inputs must produce identical results");
    }
}
