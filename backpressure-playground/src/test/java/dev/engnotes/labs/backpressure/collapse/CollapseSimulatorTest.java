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
package dev.engnotes.labs.backpressure.collapse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the behaviours that make the collapse demonstration correct. These guard the modelling
 * choices the experiment depends on; if any of them regress, the post no longer demonstrates
 * its own title.
 */
class CollapseSimulatorTest {

    // 10ms service => 100 rps capacity, 200ms deadline, effectively unbounded queue.
    private static final long SERVICE_MS = 10L;
    private static final int QUEUE_CAP = 100_000;
    private static final long DEADLINE_MS = 200L;
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_MS = 50L;
    private static final long WINDOW_MS = 5_000L;

    private CollapseSimulator simulator() {
        return new CollapseSimulator(SERVICE_MS, QUEUE_CAP, DEADLINE_MS, MAX_RETRIES, BACKOFF_MS);
    }

    @Test
    void belowCapacityEverythingSucceeds() {
        LoadLevelResult result = simulator().run(75, WINDOW_MS, false);
        assertEquals(75.0, result.goodputRps(), 0.01, "goodput should track offered load below capacity");
        assertEquals(0.0, result.wastedPct(), 0.01, "no work is wasted below capacity");
        assertTrue(result.p99Ms() <= DEADLINE_MS, "no request misses its deadline below capacity");
    }

    @Test
    void overloadCollapsesGoodputBelowCapacity() {
        double capacity = simulator().serverCapacityRps();
        LoadLevelResult result = simulator().run(150, WINDOW_MS, false);
        assertTrue(result.goodputRps() < capacity * 0.5,
                "goodput must collapse well below capacity under overload, was " + result.goodputRps());
        assertTrue(result.wastedPct() > 50.0, "most work is wasted under overload");
    }

    @Test
    void serverBurnsCapacityOnDeadWork() {
        // The mechanism: the server keeps serving requests whose clients have already given up,
        // so observed sojourn climbs far past the client deadline. If past-deadline work were
        // dropped instead of served, p99 could not exceed the deadline by this much.
        LoadLevelResult result = simulator().run(200, WINDOW_MS, false);
        assertTrue(result.p99Ms() > DEADLINE_MS * 5,
                "served-but-dead work should push p99 far past the deadline, was " + result.p99Ms());
    }

    @Test
    void retriesAmplifyLoadWithoutRescuingGoodput() {
        LoadLevelResult noRetry = simulator().run(150, WINDOW_MS, false);
        LoadLevelResult retry = simulator().run(150, WINDOW_MS, true);

        assertTrue(retry.effectiveRps() > noRetry.effectiveRps() * 1.5,
                "retries must multiply the effective load on the server");
        assertTrue(retry.effectiveRps() > retry.offeredRps(),
                "effective load exceeds offered load once retries fire");
        assertEquals(noRetry.goodputRps(), retry.goodputRps(), 0.01,
                "retries add load but do not rescue goodput on an overloaded server");
    }

    @Test
    void retriesAreInertBelowCapacity() {
        LoadLevelResult noRetry = simulator().run(90, WINDOW_MS, false);
        LoadLevelResult retry = simulator().run(90, WINDOW_MS, true);
        assertEquals(noRetry.effectiveRps(), retry.effectiveRps(), 0.01,
                "no timeouts below capacity, so no retries fire and the two modes coincide");
    }

    @Test
    void outputIsDeterministic() {
        LoadLevelResult first = simulator().run(150, WINDOW_MS, true);
        LoadLevelResult second = simulator().run(150, WINDOW_MS, true);
        assertEquals(first, second, "identical inputs must produce identical results");
    }
}
