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
package dev.engnotes.labs.commons;

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.manifest.ExperimentManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentManifestTest {

    @Test
    void writeCreatesReceiptWithCsvShaAndAbsentGoldenStatus(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("post99-sample.csv");
        Files.writeString(csv, "elapsed_s,p99_ms\n1,42.0\n");

        CliArgs args = CliArgs.builder()
                .deterministic(true)
                .duration(Duration.ofSeconds(5))
                .concurrency(10)
                .outputDir(dir)
                .snapshotInterval(Duration.ofSeconds(1))
                .build();

        Path manifest = dir.resolve("manifest.json");
        ExperimentManifest.write(
                99,
                "Sample Post",
                args,
                new String[]{"--deterministic"},
                List.of(new ExperimentManifest.CsvArtifact("sample", csv)),
                manifest);

        String json = Files.readString(manifest);
        assertTrue(json.contains("\"post_number\": 99"));
        assertTrue(json.contains("\"title\": \"Sample Post\""));
        assertTrue(json.contains("\"sha256\""));
        assertTrue(json.contains("\"golden_match\": \"absent\""));
        assertTrue(json.contains("\"command_args\": [\"--deterministic\"]"));
    }
}
