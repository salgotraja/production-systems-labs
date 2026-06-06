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
package dev.engnotes.labs.backpressure.shaping;

import dev.engnotes.labs.backpressure.admission.DemandCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the policing-vs-shaping design lessons: goodput cannot tell the gates apart, the wait
 * relocates (server side for token, gate side for leaky), only the token bucket passes bursts
 * downstream, and oversizing the burst dimension fails on each gate's own side. If these
 * regress, the post no longer makes its point.
 */
class ShapingSimulatorTest {

    // Same server as Posts 1-2: 10ms service => 100 rps capacity, 200ms deadline => budget 20.
    private static final long SERVICE_MS = 10L;
    private static final long DEADLINE_MS = 200L;
    private static final double GATE_RATE_RPS = 100.0;
    private static final long WINDOW_MS = 5_000L;

    private ShapingSimulator simulator() {
        return new ShapingSimulator(SERVICE_MS, DEADLINE_MS);
    }

    private DemandCurve bursty() {
        return DemandCurve.bursty(20.0, 1_000L, 600.0, 200L);
    }

    private ShapingPointResult token(int burst) {
        return simulator().run(bursty(), new TokenBucketGate(GATE_RATE_RPS, burst), "token-bucket", burst, WINDOW_MS);
    }

    private ShapingPointResult leaky(int burst) {
        return simulator().run(bursty(), new LeakyBucketGate(GATE_RATE_RPS, burst), "leaky-bucket", burst, WINDOW_MS);
    }

    @Test
    void deadlineBurstBudgetIsCapacityTimesDeadline() {
        assertEquals(20, simulator().deadlineBurstBudget());
    }

    @Test
    void goodputCannotTellTheGatesApart() {
        for (int burst : new int[] {4, 20, 80}) {
            double tokenGoodput = token(burst).goodputRps();
            double leakyGoodput = leaky(burst).goodputRps();
            assertTrue(Math.abs(tokenGoodput - leakyGoodput) < 0.05 * tokenGoodput,
                    "goodput should be near-identical at burst " + burst + ": "
                            + tokenGoodput + " vs " + leakyGoodput);
        }
    }

    @Test
    void waitLandsAtTheServerForTokenAndAtTheGateForLeaky() {
        ShapingPointResult token = token(20);
        ShapingPointResult leaky = leaky(20);

        assertEquals(0.0, token.gateDelayP99Ms(), "token bucket never delays at the gate");
        assertTrue(token.serverWaitP99Ms() > 100.0, "token burst queues at the server: " + token.serverWaitP99Ms());
        assertEquals(0.0, leaky.serverWaitP99Ms(), "leaky output never queues at the server");
        assertTrue(leaky.gateDelayP99Ms() > 100.0, "leaky burst waits at the gate: " + leaky.gateDelayP99Ms());
    }

    @Test
    void onlyTheTokenBucketPassesTheBurstDownstream() {
        for (int burst : new int[] {1, 20, 80}) {
            assertTrue(leaky(burst).downstreamPeakRps() <= GATE_RATE_RPS,
                    "shaping never exceeds the leak rate downstream at burst " + burst);
        }
        assertTrue(token(80).downstreamPeakRps() > 2 * GATE_RATE_RPS,
                "a big bucket floods the server at line rate: " + token(80).downstreamPeakRps());
    }

    @Test
    void deadlineBudgetIsTheSweetSpot() {
        for (boolean useToken : new boolean[] {true, false}) {
            double tight = useToken ? token(4).goodputRps() : leaky(4).goodputRps();
            double sweet = useToken ? token(20).goodputRps() : leaky(20).goodputRps();
            double loose = useToken ? token(80).goodputRps() : leaky(80).goodputRps();
            assertTrue(sweet > tight, "budget beats a tight knob: " + sweet + " vs " + tight);
            assertTrue(sweet > loose, "budget beats a loose knob: " + sweet + " vs " + loose);
        }
    }

    @Test
    void oversizedBurstDimensionFailsByServedLateOnEachGatesOwnSide() {
        ShapingPointResult token = token(80);
        ShapingPointResult leaky = leaky(80);

        assertTrue(token.servedLatePct() > 40.0, "oversized bucket serves late: " + token.servedLatePct());
        assertTrue(token.serverWaitP99Ms() > DEADLINE_MS, "the damage is server wait: " + token.serverWaitP99Ms());
        assertTrue(leaky.servedLatePct() > 40.0, "oversized queue serves late: " + leaky.servedLatePct());
        assertTrue(leaky.gateDelayP99Ms() > DEADLINE_MS, "the damage is gate delay: " + leaky.gateDelayP99Ms());
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(token(20), token(20));
        assertEquals(leaky(20), leaky(20));
    }
}
