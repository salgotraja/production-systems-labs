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
import dev.engnotes.labs.failprop.cascade.ServiceTime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the behaviours that make the retry-storm demonstration correct. The micro-cases are
 * hand-computed: with this state machine (timeout generations, abandoned-keeps-retrying,
 * backoff scheduling) a subtle bug produces wrong-but-plausible aggregate numbers, so the
 * exact spawn counts and resolution times are asserted, not just the trends.
 */
class RetryStormSimulatorTest {

    private static final long DURATION_MS = 5_000L;
    private static final long DEADLINE_MS = RetryStormScenario.CLIENT_DEADLINE_MS;
    private static final long WINDOW_MS = RetryStormScenario.WINDOW_MS;

    /** One root only: 0.1 rps puts the second arrival at 10s, outside the 5s window. */
    private static RouteDemand singleRequest() {
        return new RouteDemand("single", List.of("frontend", "service-a", "database"), 0.1);
    }

    private static RetryStormSimulator.StormOutcome scenarioRun(long dbServiceMs, int attempts) {
        return RetryStormScenario.simulator(ServiceTime.constant(dbServiceMs), attempts)
                .run(RetryStormScenario.route(), DURATION_MS, DEADLINE_MS, WINDOW_MS);
    }

    @Test
    void microCaseSpawnsExactlyAttemptsSquaredAtTheLeaf() {
        // Hand-computed, R=2, hard-down db (500ms service vs 400ms timeout):
        // frontend attempt 1 -> service-a #1 (work done t=10) -> db at 10, retry db at 460;
        // frontend times out #1 at 405, attempt 2 -> service-a #2 (spawned 455) -> db at 460,
        // retry db at 910. Four db attempts; frontend exhausts at 5 + 400 + 50 + 400 = 855.
        RetryStormSimulator.StormOutcome outcome =
                RetryStormScenario.simulator(ServiceTime.constant(500), 2)
                        .run(singleRequest(), DURATION_MS, DEADLINE_MS, WINDOW_MS);

        assertEquals(List.of(4), outcome.leafAttemptsPerRoot(), "R=2 over two retrying hops = 4 leaf attempts");
        assertEquals(0.0, outcome.successPct(), 0.01);
        assertEquals(855.0, outcome.p99ResolutionMs(), 0.01, "give-up time is the retry budget");
    }

    @Test
    void fastResponseInvalidatesThePendingTimeout() {
        // A generous timeout lets the chain succeed (db completes at 510 < 2010). The timeouts
        // scheduled for those attempts still fire later - the generation stamp must make them
        // dead letters, not second retries. A double-retry here would spawn extra db attempts.
        RetryStormSimulator simulator = new RetryStormSimulator(
                RetryStormScenario.topology(ServiceTime.constant(500)),
                new RetryPolicy(2, 2_000L, 50L));
        RetryStormSimulator.StormOutcome outcome =
                simulator.run(singleRequest(), DURATION_MS, DEADLINE_MS, WINDOW_MS);

        assertEquals(List.of(1), outcome.leafAttemptsPerRoot(), "success on the first attempt everywhere");
        assertEquals(100.0, outcome.successPct(), 0.01);
        assertEquals(510.0, outcome.p99ResolutionMs(), 0.01);
    }

    @Test
    void retriesAreInertWhenHealthy() {
        RetryStormSimulator.StormOutcome outcome = scenarioRun(RetryStormScenario.DB_HEALTHY_MS, 3);
        assertEquals(100.0, outcome.successPct(), 0.01);
        assertTrue(outcome.leafAttemptsPerRoot().stream().allMatch(n -> n == 1),
                "no attempt ever fails, so no retry ever fires");
    }

    @Test
    void abandonedCallersKeepRetryingAndCompoundTheLoad() {
        // The frontend abandons each service-a attempt at 400ms, long before service-a's own
        // ~1300ms retry budget ends - yet every abandoned service-a attempt still runs its
        // full db budget. That is the R^2: the first root's complete tree is exactly 9.
        RetryStormSimulator.StormOutcome outcome = scenarioRun(RetryStormScenario.DB_DEGRADED_MS, 3);
        assertEquals(9, outcome.leafAttemptsPerRoot().get(0),
                "3 service-a attempts x 3 db attempts each, abandonment notwithstanding");
        assertEquals(0.0, outcome.successPct(), 0.01, "all that load bought no goodput");
    }

    @Test
    void stormTimelineShowsRescueAtAmplifiedCost() {
        ServiceTime transientDb = ServiceTime.degradedBetween(
                RetryStormScenario.DEGRADE_FROM_MS, RetryStormScenario.DEGRADE_TO_MS,
                RetryStormScenario.DB_HEALTHY_MS, RetryStormScenario.DB_DEGRADED_MS);
        RetryStormSimulator.StormOutcome baseline = RetryStormScenario.simulator(transientDb, 1)
                .run(RetryStormScenario.route(), DURATION_MS, DEADLINE_MS, WINDOW_MS);
        RetryStormSimulator.StormOutcome storm = RetryStormScenario.simulator(transientDb, 3)
                .run(RetryStormScenario.route(), DURATION_MS, DEADLINE_MS, WINDOW_MS);

        long baselineFailedWindows = zeroWindows(baseline.windowSuccessPct());
        long stormFailedWindows = zeroWindows(storm.windowSuccessPct());
        assertTrue(baselineFailedWindows >= 4,
                "without retries the blip costs several windows: " + baselineFailedWindows);
        assertTrue(stormFailedWindows <= 3 && stormFailedWindows < baselineFailedWindows,
                "retries rescue most of the blip: " + stormFailedWindows);

        int baselinePeak = peak(baseline.windowAttemptsByService().get("database"));
        int stormPeak = peak(storm.windowAttemptsByService().get("database"));
        assertTrue(stormPeak >= 5 * baselinePeak,
                "the rescue is paid in database attempts: " + stormPeak + " vs " + baselinePeak);
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(scenarioRun(RetryStormScenario.DB_DEGRADED_MS, 3),
                scenarioRun(RetryStormScenario.DB_DEGRADED_MS, 3),
                "identical inputs must produce identical results");
    }

    @Test
    void rejectsInvalidRuns() {
        RetryStormSimulator simulator = RetryStormScenario.simulator(ServiceTime.constant(10), 2);
        assertThrows(IllegalArgumentException.class,
                () -> simulator.run(RetryStormScenario.route(), DEADLINE_MS, DEADLINE_MS, WINDOW_MS));
        assertThrows(IllegalArgumentException.class,
                () -> simulator.run(new RouteDemand("flat", List.of("frontend"), 50.0),
                        DURATION_MS, DEADLINE_MS, WINDOW_MS));
    }

    private static long zeroWindows(List<Double> successPct) {
        return successPct.stream().filter(pct -> pct == 0.0).count();
    }

    private static int peak(List<Integer> counts) {
        return counts.stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
