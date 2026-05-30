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
package dev.engnotes.labs.latency;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentRegistryTest {

    @Test
    void definesTheCompleteSeriesOneExperimentSet() {
        assertEquals(6, ExperimentRegistry.all().size());
        assertEquals(
                Set.of(
                        "tail-latency",
                        "queue-saturation",
                        "hedged-requests",
                        "coordinated-omission",
                        "backpressure",
                        "slo-policy"),
                ids());
    }

    @Test
    void keepsArticleAliasesUniqueAndOrdered() {
        Set<Integer> postNumbers = new HashSet<>();
        for (ExperimentDefinition experiment : ExperimentRegistry.all()) {
            assertTrue(postNumbers.add(experiment.postNumber()), "duplicate post number: " + experiment);
            assertTrue(experiment.postNumber() >= 1 && experiment.postNumber() <= 6);
        }
    }

    @Test
    void keepsTaskAndMainClassMetadataLaunchReady() {
        Set<String> taskNames = new HashSet<>();
        for (ExperimentDefinition experiment : ExperimentRegistry.all()) {
            assertFalse(experiment.id().isBlank());
            assertFalse(experiment.title().isBlank());
            assertTrue(experiment.taskName().startsWith("run"));
            assertTrue(taskNames.add(experiment.taskName()), "duplicate task name: " + experiment);
            assertEquals("dev.engnotes.labs.latency", experiment.mainClass().getPackageName());
            assertTrue(experiment.defaultOutputDir().startsWith("build"));
        }
    }

    private static Set<String> ids() {
        Set<String> ids = new HashSet<>();
        for (ExperimentDefinition experiment : ExperimentRegistry.all()) {
            assertTrue(ids.add(experiment.id()), "duplicate id: " + experiment);
        }
        return ids;
    }
}
