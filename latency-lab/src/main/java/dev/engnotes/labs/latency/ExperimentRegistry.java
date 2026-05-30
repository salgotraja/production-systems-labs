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

import java.nio.file.Path;
import java.util.List;

public final class ExperimentRegistry {

    public static final ExperimentDefinition TAIL_LATENCY = new ExperimentDefinition(
            "tail-latency",
            1,
            "Tail Latency and Fan-out Amplification",
            "runTailLatency",
            TailLatencyMain.class,
            Path.of("build", "post1"));

    public static final ExperimentDefinition QUEUE_SATURATION = new ExperimentDefinition(
            "queue-saturation",
            2,
            "Queue Saturation and Little's Law",
            "runQueueSaturation",
            QueueSaturationMain.class,
            Path.of("build", "post2"));

    public static final ExperimentDefinition HEDGED_REQUESTS = new ExperimentDefinition(
            "hedged-requests",
            3,
            "Hedged Requests and Speculative Execution",
            "runHedgedRequests",
            HedgedRequestsMain.class,
            Path.of("build", "post3"));

    public static final ExperimentDefinition COORDINATED_OMISSION = new ExperimentDefinition(
            "coordinated-omission",
            4,
            "Coordinated Omission Measurement",
            "runCoordinatedOmission",
            CoordinatedOmissionMain.class,
            Path.of("build", "post4"));

    public static final ExperimentDefinition BACKPRESSURE = new ExperimentDefinition(
            "backpressure",
            5,
            "Backpressure Strategy Comparison",
            "runBackpressure",
            BackpressureMain.class,
            Path.of("build", "post5"));

    public static final ExperimentDefinition SLO_POLICY = new ExperimentDefinition(
            "slo-policy",
            6,
            "SLO Policy and Burn-rate Simulation",
            "runSloPolicy",
            SloPolicyMain.class,
            Path.of("build", "post6"));

    private static final List<ExperimentDefinition> ALL = List.of(
            TAIL_LATENCY,
            QUEUE_SATURATION,
            HEDGED_REQUESTS,
            COORDINATED_OMISSION,
            BACKPRESSURE,
            SLO_POLICY);

    private ExperimentRegistry() {}

    public static List<ExperimentDefinition> all() {
        return ALL;
    }
}
