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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenOutputTest {

    @TempDir
    Path tempDir;

    @Test
    void deterministicRunsMatchGoldenCsvFiles() throws Exception {
        runTailLatency();
        runQueueSaturation();
        runHedgedRequests();
        runCoordinatedOmission();
        runBackpressure();
        runSloPolicy();
    }

    private void runTailLatency() throws Exception {
        Path outputDir = tempDir.resolve("post1");
        TailLatencyMain.main(args(outputDir, "--duration", "5s", "--concurrency", "10"));
        assertCsvEquals("post1/post1-baseline.csv", outputDir.resolve("post1-baseline.csv"));
        assertCsvEquals("post1/post1-tail-amplification.csv", outputDir.resolve("post1-tail-amplification.csv"));
        assertCommonArtifacts(outputDir, 1);
    }

    private void runQueueSaturation() throws Exception {
        Path outputDir = tempDir.resolve("post2");
        QueueSaturationMain.main(args(outputDir, "--duration", "2s", "--concurrency", "1"));
        assertCsvEquals("post2/post2-saturation.csv", outputDir.resolve("post2-saturation.csv"));
        assertCommonArtifacts(outputDir, 2);
    }

    private void runHedgedRequests() throws Exception {
        Path outputDir = tempDir.resolve("post3");
        HedgedRequestsMain.main(args(outputDir, "--duration", "5s", "--concurrency", "10", "--hedge-threshold", "p95"));
        assertCsvEquals("post3/post3-baseline.csv", outputDir.resolve("post3-baseline.csv"));
        assertCsvEquals("post3/post3-hedged-p95.csv", outputDir.resolve("post3-hedged-p95.csv"));
        assertCsvEquals("post3/post3-cost-table.csv", outputDir.resolve("post3-cost-table.csv"));
        assertCommonArtifacts(outputDir, 3);
    }

    private void runCoordinatedOmission() throws Exception {
        Path outputDir = tempDir.resolve("post4");
        CoordinatedOmissionMain.main(args(outputDir, "--duration", "5s", "--concurrency", "10", "--measurement-mode", "both"));
        assertCsvEquals("post4/post4-closed-loop-raw.csv", outputDir.resolve("post4-closed-loop-raw.csv"));
        assertCsvEquals("post4/post4-closed-loop-corrected.csv", outputDir.resolve("post4-closed-loop-corrected.csv"));
        assertCsvEquals("post4/post4-open-loop.csv", outputDir.resolve("post4-open-loop.csv"));
        assertCsvEquals("post4/post4-summary.csv", outputDir.resolve("post4-summary.csv"));
        assertCommonArtifacts(outputDir, 4);
    }

    private void runBackpressure() throws Exception {
        Path outputDir = tempDir.resolve("post5");
        BackpressureMain.main(args(outputDir, "--duration", "5s", "--concurrency", "10", "--backpressure-strategy", "all"));
        assertCsvEquals("post5/post5-backpressure-summary.csv", outputDir.resolve("post5-backpressure-summary.csv"));
        assertCsvEquals("post5/post5-backpressure-snapshots.csv", outputDir.resolve("post5-backpressure-snapshots.csv"));
        assertCommonArtifacts(outputDir, 5);
    }

    private void runSloPolicy() throws Exception {
        Path outputDir = tempDir.resolve("post6");
        SloPolicyMain.main(args(outputDir, "--duration", "5s", "--concurrency", "10", "--slo-target", "p99<200ms"));
        assertCsvEquals("post6/post6-slo-summary.csv", outputDir.resolve("post6-slo-summary.csv"));
        assertCsvEquals("post6/post6-slo-windows.csv", outputDir.resolve("post6-slo-windows.csv"));
        assertCommonArtifacts(outputDir, 6);
    }

    private String[] args(Path outputDir, String... extraArgs) {
        String[] args = new String[extraArgs.length + 5];
        args[0] = "--deterministic";
        args[1] = "--output-dir";
        args[2] = outputDir.toString();
        args[3] = "--snapshot-interval";
        args[4] = "1s";
        System.arraycopy(extraArgs, 0, args, 5, extraArgs.length);
        return args;
    }

    private void assertCsvEquals(String goldenRelativePath, Path actual) throws Exception {
        Path expected = Path.of(System.getProperty("golden.dir")).resolve(goldenRelativePath);
        List<String> expectedLines = Files.readAllLines(expected);
        List<String> actualLines = Files.readAllLines(actual);
        assertEquals(expectedLines, actualLines, goldenRelativePath);
    }

    private void assertCommonArtifacts(Path outputDir, int postNumber) throws Exception {
        String manifest = Files.readString(outputDir.resolve("manifest.json"));
        String report = Files.readString(outputDir.resolve("report.html"));
        assertTrue(manifest.contains("\"post_number\": " + postNumber), "manifest post number");
        assertTrue(manifest.contains("\"golden_match\": true"), "manifest golden match");
        assertFalse(manifest.contains("\"golden_match\": false"), "manifest golden mismatch");
        assertFalse(manifest.contains("\"golden_match\": \"absent\""), "manifest absent golden");
        assertTrue(report.contains("report-data"), "report data payload");
        assertFalse(report.contains("innerHTML"), "report table must render through DOM text nodes");
        assertTrue(Files.size(outputDir.resolve("report.html")) < 250 * 1024, "report size guardrail");
    }
}
