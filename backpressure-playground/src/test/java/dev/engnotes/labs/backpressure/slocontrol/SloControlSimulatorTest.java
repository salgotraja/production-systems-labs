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
package dev.engnotes.labs.backpressure.slocontrol;

import dev.engnotes.labs.backpressure.admission.DemandCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the capstone lessons: served latency is deadline-flat for both policies (a latency SLO
 * cannot tell them apart), blind shedding degrades the classes together, priority holds the
 * critical success SLO until the protection ceiling and not beyond, and the background class
 * pays the bill. If these regress, the post no longer makes its point.
 */
class SloControlSimulatorTest {

    // Same server as Posts 1-4: 10ms service => 100 rps capacity, 200ms deadline => bound 20.
    private static final long SERVICE_MS = 10L;
    private static final long DEADLINE_MS = 200L;
    private static final double CRITICAL_SHARE = 0.25;
    private static final long WINDOW_MS = 5_000L;

    private SloControlSimulator simulator() {
        return new SloControlSimulator(SERVICE_MS, DEADLINE_MS, CRITICAL_SHARE);
    }

    private SloPointResult run(ClassPolicy policy, double offeredRps) {
        return simulator().run(DemandCurve.constant(offeredRps), policy, WINDOW_MS);
    }

    @Test
    void boundAndCeilingFollowTheDeadlineArithmetic() {
        assertEquals(20, simulator().deadlineQueueBound());
        assertEquals(400.0, simulator().protectionCeilingRps());
    }

    @Test
    void servedLatencyIsDeadlineFlatForBothPolicies() {
        for (ClassPolicy policy : ClassPolicy.values()) {
            SloPointResult overloaded = run(policy, 300.0);
            assertTrue(overloaded.criticalP99Ms() <= DEADLINE_MS,
                    policy.label() + " critical p99 capped by the bound: " + overloaded.criticalP99Ms());
            assertTrue(overloaded.backgroundP99Ms() <= DEADLINE_MS,
                    policy.label() + " background p99 capped by the bound: " + overloaded.backgroundP99Ms());
        }
    }

    @Test
    void blindSheddingDegradesTheClassesTogether() {
        for (double offeredRps : new double[] {200.0, 300.0}) {
            SloPointResult result = run(ClassPolicy.BLIND, offeredRps);
            assertTrue(Math.abs(result.criticalSuccessPct() - result.backgroundSuccessPct()) < 5.0,
                    "blind spreads the pain evenly at " + offeredRps + ": "
                            + result.criticalSuccessPct() + " vs " + result.backgroundSuccessPct());
            assertFalse(result.criticalSloMet(), "blind loses the critical SLO under overload");
        }
    }

    @Test
    void priorityHoldsTheCriticalSloBelowTheCeiling() {
        for (double offeredRps : new double[] {150.0, 200.0, 300.0}) {
            SloPointResult priority = run(ClassPolicy.PRIORITY, offeredRps);
            assertTrue(priority.criticalSloMet(),
                    "priority holds the SLO at " + offeredRps + ": " + priority.criticalSuccessPct());
            assertFalse(run(ClassPolicy.BLIND, offeredRps).criticalSloMet(),
                    "blind does not at " + offeredRps);
        }
    }

    @Test
    void protectionBreaksAtTheCeiling() {
        SloPointResult atCeiling = run(ClassPolicy.PRIORITY, 400.0);
        assertFalse(atCeiling.criticalSloMet(),
                "critical alone reaches capacity at the ceiling: " + atCeiling.criticalSuccessPct());
        SloPointResult beyond = run(ClassPolicy.PRIORITY, 500.0);
        assertTrue(beyond.criticalSuccessPct() < atCeiling.criticalSuccessPct(),
                "beyond the ceiling protection is arithmetic-impossible: " + beyond.criticalSuccessPct());
        assertTrue(beyond.criticalSuccessPct() > run(ClassPolicy.BLIND, 500.0).criticalSuccessPct(),
                "priority still beats blind beyond the ceiling");
    }

    @Test
    void theBackgroundClassPaysTheBill() {
        SloPointResult priority = run(ClassPolicy.PRIORITY, 300.0);
        SloPointResult blind = run(ClassPolicy.BLIND, 300.0);
        assertTrue(priority.backgroundSuccessPct() < blind.backgroundSuccessPct(),
                "protection is paid for in background traffic: "
                        + priority.backgroundSuccessPct() + " vs " + blind.backgroundSuccessPct());
        assertTrue(priority.criticalSuccessPct() > 99.0, "and buys the critical SLO");
    }

    @Test
    void priorityHoldsTheCriticalLineThroughTheSpike() {
        DemandCurve curve = DemandCurve.bursty(80.0, 1_000L, 360.0, 500L);
        SloControlSimulator.WindowOutcome blind =
                simulator().successPerWindow(curve, ClassPolicy.BLIND, WINDOW_MS, 100L);
        SloControlSimulator.WindowOutcome priority =
                simulator().successPerWindow(curve, ClassPolicy.PRIORITY, WINDOW_MS, 100L);

        // First spike is 1000..1500ms; window 12 is in its middle.
        assertTrue(priority.criticalPct()[12] >= 99.0,
                "priority holds critical through the spike: " + priority.criticalPct()[12]);
        assertTrue(blind.criticalPct()[12] < 75.0,
                "blind lets critical dive with everyone else: " + blind.criticalPct()[12]);
    }

    @Test
    void outputIsDeterministic() {
        for (ClassPolicy policy : ClassPolicy.values()) {
            assertEquals(run(policy, 300.0), run(policy, 300.0));
        }
    }

    @Test
    void rejectsWindowsShorterThanTheDeadline() {
        // duration <= deadline leaves no scoring window: rates inflate and the
        // per-window array size goes negative. Both entry points must refuse it.
        assertThrows(IllegalArgumentException.class,
                () -> simulator().run(DemandCurve.constant(100.0), ClassPolicy.BLIND, DEADLINE_MS));
        assertThrows(IllegalArgumentException.class,
                () -> simulator().successPerWindow(DemandCurve.constant(100.0), ClassPolicy.PRIORITY, 100L, 100L));
    }
}
