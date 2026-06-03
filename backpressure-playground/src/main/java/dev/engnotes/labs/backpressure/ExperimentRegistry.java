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

import java.nio.file.Path;
import java.util.List;

/**
 * Java-side source of durable experiment metadata for the backpressure-playground (Series 2).
 * Gradle mirrors this metadata to register run tasks (ADR-006).
 */
public final class ExperimentRegistry {

    public static final ExperimentDefinition LOAD_COLLAPSE = new ExperimentDefinition(
            "load-collapse",
            1,
            "Why Systems Collapse Under Load",
            "runLoadCollapse",
            LoadCollapseMain.class,
            Path.of("build", "bp-post1"),
            Path.of("golden", "bp-post1"));

    public static final ExperimentDefinition ADMISSION_CONTROL = new ExperimentDefinition(
            "admission-control",
            2,
            "Admission Control Design",
            "runAdmissionControl",
            AdmissionControlMain.class,
            Path.of("build", "bp-post2"),
            Path.of("golden", "bp-post2"));

    private static final List<ExperimentDefinition> ALL = List.of(LOAD_COLLAPSE, ADMISSION_CONTROL);

    private ExperimentRegistry() {}

    public static List<ExperimentDefinition> all() {
        return ALL;
    }
}
