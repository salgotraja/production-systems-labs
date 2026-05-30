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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloScenarioTest {

    @Test
    void scenarioProducesOneSummaryPerArchitecturePattern() {
        SloRunResult result = new SloScenario().run(new SloTarget(99.0, 200), 5, 10);

        assertEquals(ArchitecturePattern.values().length, result.summaries().size());
        assertEquals(ArchitecturePattern.values().length * 5, result.windows().size());
    }

    @Test
    void baselineBurnsMoreBudgetThanConcurrencyLimit() {
        SloRunResult result = new SloScenario().run(new SloTarget(99.0, 200), 5, 10);

        SloSummary baseline = result.summaries().stream()
                .filter(summary -> summary.pattern() == ArchitecturePattern.BASELINE)
                .findFirst()
                .orElseThrow();
        SloSummary concurrencyLimit = result.summaries().stream()
                .filter(summary -> summary.pattern() == ArchitecturePattern.CONCURRENCY_LIMIT)
                .findFirst()
                .orElseThrow();

        assertTrue(baseline.badRequests() > concurrencyLimit.badRequests());
    }
}
