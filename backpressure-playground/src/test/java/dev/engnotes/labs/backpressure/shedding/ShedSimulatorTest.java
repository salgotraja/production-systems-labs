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
package dev.engnotes.labs.backpressure.shedding;

import dev.engnotes.labs.backpressure.admission.DemandCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shedding-policy fingerprints: only fifo collapses, the real policies are collinear on
 * goodput, lifo serves fresh work while the FIFO-order policies serve near-deadline work, the
 * shed wait spans the fast/slow/never spectrum, and fifo's hangover outlives the burst. If these
 * regress, the post no longer makes its point.
 */
class ShedSimulatorTest {

    // Same server as Posts 1-3: 10ms service => 100 rps capacity, 200ms deadline => bound 20.
    private static final long SERVICE_MS = 10L;
    private static final long DEADLINE_MS = 200L;
    private static final long WINDOW_MS = 5_000L;
    private static final double OVERLOAD_RPS = 200.0;

    private ShedSimulator simulator() {
        return new ShedSimulator(SERVICE_MS, DEADLINE_MS);
    }

    private ShedPointResult run(ShedPolicy policy, double offeredRps) {
        return simulator().run(DemandCurve.constant(offeredRps), policy, WINDOW_MS);
    }

    @Test
    void deadlineQueueBoundIsCapacityTimesDeadline() {
        assertEquals(20, simulator().deadlineQueueBound());
    }

    @Test
    void onlyFifoCollapsesUnderOverload() {
        double capacity = simulator().serverCapacityRps();
        assertTrue(run(ShedPolicy.FIFO, OVERLOAD_RPS).goodputRps() < 0.25 * capacity,
                "no shedding collapses goodput");
        for (ShedPolicy policy : new ShedPolicy[] {ShedPolicy.TAIL_DROP, ShedPolicy.EXPIRE, ShedPolicy.LIFO}) {
            double goodput = run(policy, OVERLOAD_RPS).goodputRps();
            assertTrue(goodput > 0.95 * capacity,
                    policy.label() + " holds goodput near capacity: " + goodput);
        }
    }

    @Test
    void lifoServesFreshWorkWhileFifoOrderPoliciesServeNearDeadlineWork() {
        assertTrue(run(ShedPolicy.LIFO, OVERLOAD_RPS).p99ServedMs() < 50.0,
                "lifo serves the newest through any backlog");
        for (ShedPolicy policy : new ShedPolicy[] {ShedPolicy.TAIL_DROP, ShedPolicy.EXPIRE}) {
            double p99 = run(policy, OVERLOAD_RPS).p99ServedMs();
            assertTrue(p99 > 150.0 && p99 <= DEADLINE_MS + SERVICE_MS,
                    policy.label() + " serves near-deadline work: " + p99);
        }
    }

    @Test
    void expireNeverServesLateAtAnyLoad() {
        for (double offeredRps : new double[] {125.0, 200.0, 300.0}) {
            ShedPointResult result = run(ShedPolicy.EXPIRE, offeredRps);
            assertEquals(0.0, result.servedLatePct(), "expire never burns a slot on doomed work");
            assertEquals(0.0, result.wastedPct(), "expire wastes no service time");
        }
    }

    @Test
    void shedWaitSpansTheFastSlowNeverSpectrum() {
        assertEquals(0.0, run(ShedPolicy.TAIL_DROP, OVERLOAD_RPS).shedWaitP50Ms(),
                "tail-drop fast-fails at the door");
        double expireWait = run(ShedPolicy.EXPIRE, OVERLOAD_RPS).shedWaitP50Ms();
        assertTrue(expireWait > 150.0 && expireWait <= DEADLINE_MS,
                "expire slow-fails around the deadline: " + expireWait);
        assertTrue(run(ShedPolicy.LIFO, OVERLOAD_RPS).shedWaitP50Ms() > 1_000.0,
                "lifo never tells the starved");
    }

    @Test
    void fifoHangoverOutlivesTheBurst() {
        DemandCurve curve = DemandCurve.bursty(80.0, 1_000L, 600.0, 500L);
        double[] fifo = simulator().servedP99PerWindow(curve, ShedPolicy.FIFO, WINDOW_MS, 100L);
        double[] expire = simulator().servedP99PerWindow(curve, ShedPolicy.EXPIRE, WINDOW_MS, 100L);
        double[] lifo = simulator().servedP99PerWindow(curve, ShedPolicy.LIFO, WINDOW_MS, 100L);

        // First spike is 1000..1500ms; window 20 starts 500ms after it ended.
        assertTrue(fifo[20] > 2 * DEADLINE_MS, "fifo still pays after the spike: " + fifo[20]);
        assertTrue(fifo[49] > fifo[20], "fifo backlog compounds across spikes: " + fifo[49]);
        assertTrue(expire[20] <= DEADLINE_MS + SERVICE_MS, "expire caps the hangover: " + expire[20]);
        assertTrue(lifo[12] < 50.0, "lifo stays fresh inside the spike: " + lifo[12]);
    }

    @Test
    void outputIsDeterministic() {
        for (ShedPolicy policy : ShedPolicy.values()) {
            assertEquals(run(policy, OVERLOAD_RPS), run(policy, OVERLOAD_RPS));
        }
    }
}
