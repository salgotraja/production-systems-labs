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
package dev.engnotes.labs.failprop;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Durable, topic-based metadata for one failure-propagation-lab experiment (ADR-006).
 *
 * <p>Like the Series 2 definition, this record carries an explicit {@code goldenDir}.
 * Series 3 nests its golden files under {@code golden/fp-post{N}} so all series share the
 * {@code golden/} root without post-number collisions.
 */
public record ExperimentDefinition(
        String id,
        int postNumber,
        String title,
        String taskName,
        Class<?> mainClass,
        Path defaultOutputDir,
        Path goldenDir) {

    public ExperimentDefinition {
        requireText(id, "id");
        if (postNumber < 1) {
            throw new IllegalArgumentException("postNumber must be positive");
        }
        requireText(title, "title");
        requireText(taskName, "taskName");
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(defaultOutputDir, "defaultOutputDir");
        Objects.requireNonNull(goldenDir, "goldenDir");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
