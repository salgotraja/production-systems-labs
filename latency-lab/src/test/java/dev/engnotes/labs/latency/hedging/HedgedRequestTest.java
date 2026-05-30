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
package dev.engnotes.labs.latency.hedging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HedgedRequestTest {

    @Test
    void executeDoesNotHedgeWhenPrimaryFinishesBeforeDelay() {
        HedgedRequestResult result = new HedgedRequest(true).execute(20, 10, 30);

        assertEquals(20, result.latencyMs());
        assertFalse(result.hedgeLaunched());
        assertEquals(1, result.attempts());
        assertEquals(0, result.extraAttempts());
    }

    @Test
    void deterministicResultReturnsSecondaryWhenItWins() {
        HedgedRequestResult result = HedgedRequest.deterministicResult(500, 20, 30);

        assertEquals(50, result.latencyMs());
        assertTrue(result.hedgeLaunched());
        assertEquals(2, result.attempts());
        assertEquals(1, result.extraAttempts());
        assertEquals(50, result.wastedWorkMs());
    }

    @Test
    void deterministicResultReturnsPrimaryWhenItWinsAfterHedgeStarts() {
        HedgedRequestResult result = HedgedRequest.deterministicResult(40, 100, 30);

        assertEquals(40, result.latencyMs());
        assertTrue(result.hedgeLaunched());
        assertEquals(10, result.wastedWorkMs());
    }

    @Test
    void liveExecutionUsesHedgedAttemptWhenSecondaryWins() {
        HedgedRequestResult result = new HedgedRequest(false).execute(40, 1, 5);

        assertEquals(6, result.latencyMs());
        assertTrue(result.hedgeLaunched());
        assertEquals(2, result.attempts());
    }
}
