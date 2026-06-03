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
    void loadCollapseMatchesGoldenCsv() throws Exception {
        Path outputDir = tempDir.resolve("bp-post1");
        LoadCollapseMain.main(args(outputDir, "--duration", "5s"));
        assertCsvEquals("bp-post1/bp-post1-collapse-sweep.csv", outputDir.resolve("bp-post1-collapse-sweep.csv"));
        assertCommonArtifacts(outputDir, 1);
    }

    @Test
    void admissionControlMatchesGoldenCsv() throws Exception {
        Path outputDir = tempDir.resolve("bp-post2");
        AdmissionControlMain.main(args(outputDir, "--duration", "5s"));
        assertCsvEquals("bp-post2/bp-post2-limit-sweep.csv", outputDir.resolve("bp-post2-limit-sweep.csv"));
        assertCsvEquals("bp-post2/bp-post2-plateau.csv", outputDir.resolve("bp-post2-plateau.csv"));
        assertCommonArtifacts(outputDir, 2);
    }

    private String[] args(Path outputDir, String... extraArgs) {
        String[] args = new String[extraArgs.length + 3];
        args[0] = "--deterministic";
        args[1] = "--output-dir";
        args[2] = outputDir.toString();
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
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
