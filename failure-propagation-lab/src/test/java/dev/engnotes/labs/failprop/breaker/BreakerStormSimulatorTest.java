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

import dev.engnotes.labs.failprop.cascade.ServiceTime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the breaker buys and what it cannot. The naive-equivalence test is the load-bearing
 * one: this simulator is a deliberate copy of Post 2's machine, and a single-route run with an
 * empty breaker bank must reproduce Post 2's golden numbers exactly - the same cross-check
 * discipline as Series 2's {@code AdmissionSimulator@NO_LIMIT}.
 */
class BreakerStormSimulatorTest {

    private static final long DURATION_MS = 5_000L;
    private static final long SWEEP_DEADLINE_MS = BreakerStormScenario.CLIENT_DEADLINE_MS;
    private static final long BLAST_DEADLINE_MS = BreakerStormScenario.BLAST_DEADLINE_MS;
    private static final long WINDOW_MS = BreakerStormScenario.WINDOW_MS;

    private static BreakerStormSimulator.BreakerOutcome hardDown(Map<String, CircuitBreaker> breakers) {
        return new BreakerStormSimulator(
                BreakerStormScenario.chainTopology(ServiceTime.constant(BreakerStormScenario.DB_DEGRADED_MS)),
                BreakerStormScenario.RETRY_POLICY, breakers)
                .run(List.of(BreakerStormScenario.chainRoute()), DURATION_MS, SWEEP_DEADLINE_MS, WINDOW_MS);
    }

    private static BreakerStormSimulator.BreakerOutcome blast(Map<String, CircuitBreaker> breakers) {
        return new BreakerStormSimulator(
                BreakerStormScenario.blastTopology(), BreakerStormScenario.RETRY_POLICY, breakers)
                .run(BreakerStormScenario.blastRoutes(), DURATION_MS, BLAST_DEADLINE_MS, WINDOW_MS);
    }

    @Test
    void naiveRunReproducesPostTwoExactly() {
        // Empty breaker bank, Post 2's chain and policy: Post 2's golden numbers, exactly.
        BreakerStormSimulator.BreakerOutcome outcome = hardDown(Map.of());
        BreakerStormSimulator.RouteResult route = outcome.routes().get(0);
        assertEquals(0.0, route.successPct(), 0.01);
        assertEquals(1305.0, route.p99ResolutionMs(), 0.01, "the full retry budget, Post 2's value");
        assertEquals(9, outcome.leafAttemptsPerRoot().get(0), "R^2 for the first complete tree");
        assertEquals(1847L, outcome.attemptsByService().get("database"),
                "369.4 rps x 5s, Post 2's golden total");
    }

    @Test
    void breakerCollapsesTheStormButBuysNoSuccesses() {
        BreakerStormSimulator.BreakerOutcome outcome = hardDown(BreakerStormScenario.chainBreakers());
        BreakerStormSimulator.RouteResult route = outcome.routes().get(0);
        assertEquals(0.0, route.successPct(), 0.01, "nothing rescues a hard-down dependency");
        assertTrue(outcome.attemptsByService().get("database") < 100L,
                "probes only, vs the naive 1847: " + outcome.attemptsByService().get("database"));
        assertTrue(route.p50ResolutionMs() < 200.0,
                "median failure is a fast fail, not a 1305ms hang: " + route.p50ResolutionMs());
    }

    @Test
    void breakerContainsTheBlastRadius() {
        BreakerStormSimulator.BreakerOutcome naive = blast(Map.of());
        BreakerStormSimulator.BreakerOutcome breakered = blast(BreakerStormScenario.blastBreakers());

        List<Double> naiveRouteB = naive.routes().get(1).windowSuccessPct();
        List<Double> breakeredRouteB = breakered.routes().get(1).windowSuccessPct();
        long naiveDeadWindows = naiveRouteB.stream().filter(pct -> pct == 0.0).count();
        assertTrue(naiveDeadWindows >= 4,
                "naive retries kill the route that never touches the database: " + naiveDeadWindows);
        assertTrue(breakeredRouteB.stream().allMatch(pct -> pct == 100.0),
                "with breakers route-b never drops a window");
    }

    @Test
    void breakerSparesTheDatabaseWhileOpen() {
        BreakerStormSimulator.BreakerOutcome naive = blast(Map.of());
        BreakerStormSimulator.BreakerOutcome breakered = blast(BreakerStormScenario.blastBreakers());

        // Windows 2100-2399: both breakers are open; the naive run is mid-storm.
        int naiveSum = 0;
        int breakeredSum = 0;
        for (int w = 21; w <= 23; w++) {
            naiveSum += naive.windowAttemptsByService().get("database").get(w);
            breakeredSum += breakered.windowAttemptsByService().get("database").get(w);
        }
        assertEquals(0, breakeredSum, "an open breaker sends nothing downstream");
        assertTrue(naiveSum >= 20, "the naive run keeps hammering: " + naiveSum);
    }

    @Test
    void recoveryIsOneNestedProbePass() {
        BreakerStormSimulator.BreakerOutcome breakered = blast(BreakerStormScenario.blastBreakers());

        List<CircuitBreaker.Transition> frontendEdge =
                breakered.breakerTransitions().get(BreakerStormScenario.EDGE_FRONTEND_A);
        List<CircuitBreaker.Transition> databaseEdge =
                breakered.breakerTransitions().get(BreakerStormScenario.EDGE_A_DATABASE);

        assertEquals(3, frontendEdge.size(), "open, half-open, closed - one probe cycle");
        assertEquals(3, databaseEdge.size(), "the frontend probe drives the database probe");
        assertEquals(CircuitBreaker.State.CLOSED, frontendEdge.get(2).to());
        assertEquals(CircuitBreaker.State.CLOSED, databaseEdge.get(2).to());
        // The single frontend probe traverses the chain and re-closes both edges together.
        assertEquals(frontendEdge.get(2).timeMs(), databaseEdge.get(2).timeMs(), 0.01);
        assertTrue(breakered.breakerTransitions().get(BreakerStormScenario.EDGE_FRONTEND_B).isEmpty(),
                "the breaker on the healthy edge never moves");
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(blast(BreakerStormScenario.blastBreakers()), blast(BreakerStormScenario.blastBreakers()),
                "identical inputs must produce identical results");
    }
}
