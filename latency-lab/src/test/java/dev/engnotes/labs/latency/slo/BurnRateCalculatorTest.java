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
package dev.engnotes.labs.latency.slo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BurnRateCalculatorTest {

    @Test
    void burnRateComparesBadRatioToErrorBudget() {
        SloTarget target = new SloTarget(99.0, 200);

        assertEquals(5.0, BurnRateCalculator.burnRate(50, 1_000, target));
    }

    @Test
    void burnRateIsZeroWhenNoEventsArrived() {
        SloTarget target = new SloTarget(99.0, 200);

        assertEquals(0.0, BurnRateCalculator.burnRate(0, 0, target));
    }
}
