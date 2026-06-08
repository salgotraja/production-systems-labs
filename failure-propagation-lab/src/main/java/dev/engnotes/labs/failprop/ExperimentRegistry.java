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
import java.util.List;

/**
 * Java-side source of durable experiment metadata for the failure-propagation-lab (Series 3).
 * Gradle mirrors this metadata to register run tasks (ADR-006).
 */
public final class ExperimentRegistry {

    public static final ExperimentDefinition CASCADING_FAILURES = new ExperimentDefinition(
            "cascading-failures",
            1,
            "Cascading Failures Explained",
            "runCascadingFailures",
            CascadingFailuresMain.class,
            Path.of("build", "fp-post1"),
            Path.of("golden", "fp-post1"));

    public static final ExperimentDefinition RETRY_STORMS = new ExperimentDefinition(
            "retry-storms",
            2,
            "Retry Storms and Amplification",
            "runRetryStorms",
            RetryStormsMain.class,
            Path.of("build", "fp-post2"),
            Path.of("golden", "fp-post2"));

    public static final ExperimentDefinition CIRCUIT_BREAKER = new ExperimentDefinition(
            "circuit-breaker",
            3,
            "Circuit Breaker Design",
            "runCircuitBreaker",
            CircuitBreakerMain.class,
            Path.of("build", "fp-post3"),
            Path.of("golden", "fp-post3"));

    public static final ExperimentDefinition TIMEOUT_BUDGETS = new ExperimentDefinition(
            "timeout-budgets",
            4,
            "Timeout Budgeting",
            "runTimeoutBudgets",
            TimeoutBudgetsMain.class,
            Path.of("build", "fp-post4"),
            Path.of("golden", "fp-post4"));

    public static final ExperimentDefinition FAILURE_ISOLATION = new ExperimentDefinition(
            "failure-isolation",
            5,
            "Failure Isolation Boundaries",
            "runFailureIsolation",
            FailureIsolationMain.class,
            Path.of("build", "fp-post5"),
            Path.of("golden", "fp-post5"));

    private static final List<ExperimentDefinition> ALL = List.of(
            CASCADING_FAILURES, RETRY_STORMS, CIRCUIT_BREAKER, TIMEOUT_BUDGETS, FAILURE_ISOLATION);

    private ExperimentRegistry() {}

    public static List<ExperimentDefinition> all() {
        return ALL;
    }
}
