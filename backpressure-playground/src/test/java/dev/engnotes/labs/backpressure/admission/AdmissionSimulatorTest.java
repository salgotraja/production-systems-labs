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
package dev.engnotes.labs.backpressure.admission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the admission-control design lessons: the Little's-Law sweet spot, the two failure modes
 * around it, and the plateau restoration. If these regress, the post no longer makes its point.
 */
class AdmissionSimulatorTest {

    // 10ms service => 100 rps capacity, 200ms deadline => Little's-Law limit 20.
    private static final long SERVICE_MS = 10L;
    private static final long DEADLINE_MS = 200L;
    private static final long WINDOW_MS = 5_000L;

    private AdmissionSimulator simulator() {
        return new AdmissionSimulator(SERVICE_MS, DEADLINE_MS);
    }

    private DemandCurve bursty() {
        return DemandCurve.bursty(50.0, 200L, 300.0, 100L);
    }

    @Test
    void littlesLawLimitIsCapacityTimesDeadline() {
        assertEquals(20, simulator().littlesLawLimit());
    }

    @Test
    void goodputPeaksNearTheLittlesLawLimit() {
        AdmissionSimulator sim = simulator();
        double tight = sim.run(bursty(), 4, WINDOW_MS).goodputRps();
        double sweet = sim.run(bursty(), 20, WINDOW_MS).goodputRps();
        double loose = sim.run(bursty(), 80, WINDOW_MS).goodputRps();

        assertTrue(sweet > tight, "sweet-spot goodput must beat a too-tight limit: " + sweet + " vs " + tight);
        assertTrue(sweet > loose, "sweet-spot goodput must beat a too-loose limit: " + sweet + " vs " + loose);
        assertTrue(sweet > 0.9 * sim.serverCapacityRps(), "sweet spot should approach capacity: " + sweet);
    }

    @Test
    void tooTightLimitUnderutilizesTheServer() {
        // A tight limit rejects burst traffic the valley could have served, so the server idles.
        AdmissionPointResult tight = simulator().run(bursty(), 1, WINDOW_MS);
        assertTrue(tight.utilizationPct() < 75.0, "tight limit should leave the server idle: " + tight.utilizationPct());
        assertTrue(tight.rejectPct() > 40.0, "tight limit rejects heavily: " + tight.rejectPct());
    }

    @Test
    void tooLooseLimitCollapsesIntoServedLateWork() {
        AdmissionPointResult loose = simulator().run(bursty(), AdmissionSimulator.NO_LIMIT, WINDOW_MS);
        assertTrue(loose.goodputRps() < 0.25 * simulator().serverCapacityRps(),
                "no control collapses goodput: " + loose.goodputRps());
        assertTrue(loose.servedLatePct() > 50.0, "collapse is dominated by served-late work: " + loose.servedLatePct());
    }

    @Test
    void admissionControlRestoresGoodputUnderOverload() {
        DemandCurve overload = DemandCurve.constant(300.0);
        double noControl = simulator().run(overload, AdmissionSimulator.NO_LIMIT, WINDOW_MS).goodputRps();
        double limited = simulator().run(overload, simulator().littlesLawLimit(), WINDOW_MS).goodputRps();
        assertTrue(limited > 5 * noControl, "admission control must rescue goodput: " + limited + " vs " + noControl);
        assertTrue(limited > 0.9 * simulator().serverCapacityRps(), "limited goodput holds near capacity: " + limited);
    }

    @Test
    void outputIsDeterministic() {
        AdmissionPointResult first = simulator().run(bursty(), 20, WINDOW_MS);
        AdmissionPointResult second = simulator().run(bursty(), 20, WINDOW_MS);
        assertEquals(first, second);
    }
}
