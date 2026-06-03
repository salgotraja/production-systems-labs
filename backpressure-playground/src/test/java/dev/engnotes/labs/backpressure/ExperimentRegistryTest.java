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
package dev.engnotes.labs.backpressure;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentRegistryTest {

    @Test
    void definesTheReleasedSeriesTwoExperimentSet() {
        Set<String> ids = new HashSet<>();
        Set<Integer> postNumbers = new HashSet<>();
        for (ExperimentDefinition experiment : ExperimentRegistry.all()) {
            assertTrue(ids.add(experiment.id()), "duplicate id: " + experiment);
            assertTrue(postNumbers.add(experiment.postNumber()), "duplicate post number: " + experiment);
        }
        assertEquals(Set.of("load-collapse", "admission-control"), ids);
    }

    @Test
    void keepsTaskAndGoldenMetadataLaunchReady() {
        Set<String> taskNames = new HashSet<>();
        for (ExperimentDefinition experiment : ExperimentRegistry.all()) {
            assertFalse(experiment.id().isBlank());
            assertFalse(experiment.title().isBlank());
            assertTrue(experiment.postNumber() >= 1);
            assertTrue(experiment.taskName().startsWith("run"));
            assertTrue(taskNames.add(experiment.taskName()), "duplicate task name: " + experiment);
            assertEquals("dev.engnotes.labs.backpressure", experiment.mainClass().getPackageName());
            assertTrue(experiment.defaultOutputDir().startsWith("build"));
            // Series 2 nests golden output under golden/bp-post{N} (flat), never colliding
            // with Series 1's golden/post{N}.
            assertTrue(experiment.goldenDir().startsWith("golden"));
            assertEquals("bp-post" + experiment.postNumber(),
                    experiment.goldenDir().getFileName().toString());
        }
    }
}
