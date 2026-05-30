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
package dev.engnotes.labs.latency.measurement;

import dev.engnotes.labs.commons.cli.CliArgs;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatedOmissionDemoTest {

    @Test
    void openLoopRecordsRequestsMissedByClosedLoop() {
        CliArgs args = CliArgs.builder()
                .deterministic(true)
                .duration(Duration.ofSeconds(5))
                .snapshotInterval(Duration.ofSeconds(1))
                .concurrency(10)
                .build();

        CoordinatedOmissionResult result = new CoordinatedOmissionDemo().run(args, MeasurementMode.BOTH);

        assertEquals(10, result.expectedIntervalMs());
        assertEquals(500, result.openLoop().totalRequests());
        assertTrue(result.closedLoopRaw().totalRequests() < result.openLoop().totalRequests());
        assertTrue(result.openLoop().wholeRunP99Ms() > result.closedLoopRaw().wholeRunP99Ms());
        assertTrue(result.closedLoopCorrected().wholeRunP99Ms() > result.closedLoopRaw().wholeRunP99Ms());
    }
}
