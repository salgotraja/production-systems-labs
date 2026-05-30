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

import dev.engnotes.labs.commons.report.ReportIndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportIndexWriterTest {

    @Test
    void writeEmbedsManifestAndCsvRowsWithoutExternalDependencies(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"post_number\":1,\"title\":\"Sample\"}");
        Path csv = dir.resolve("sample.csv");
        Files.writeString(csv, "elapsed_s,p50_ms,p99_ms\n1,10.0,50.0\n2,11.0,70.0\n");

        Path report = dir.resolve("report.html");
        ReportIndexWriter.write(
                1,
                "Sample",
                report,
                manifest,
                List.of(new ReportIndexWriter.Series("sample", csv)));

        String html = Files.readString(report);
        assertTrue(html.contains("report-data"));
        assertTrue(html.contains("\"p99_ms\":50.0"));
        assertTrue(html.contains("\"post_number\":1"));
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("<link href="));
        assertFalse(html.contains("innerHTML"));
        assertTrue(Files.size(report) < 250 * 1024);
    }

    @Test
    void writeIncludesTruthFlagControls(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"post_number\":4,\"title\":\"Measurement\"}");
        Path raw = dir.resolve("raw.csv");
        Files.writeString(raw, "elapsed_s,p50_ms,p99_ms,p999_ms\n1,10.0,10.0,10.0\n");
        Path corrected = dir.resolve("corrected.csv");
        Files.writeString(corrected, "elapsed_s,p50_ms,p99_ms,p999_ms\n1,10.0,460.0,460.0\n");
        Path openLoop = dir.resolve("open.csv");
        Files.writeString(openLoop, "elapsed_s,p50_ms,p99_ms,p999_ms\n1,10.0,460.0,460.0\n");

        Path report = dir.resolve("report.html");
        ReportIndexWriter.write(
                4,
                "Measurement",
                report,
                manifest,
                List.of(
                        new ReportIndexWriter.Series("Closed-loop raw", raw),
                        new ReportIndexWriter.Series("Closed-loop corrected", corrected),
                        new ReportIndexWriter.Series("Open-loop", openLoop)));

        String html = Files.readString(report);
        assertTrue(html.contains("measurement-controls"));
        assertTrue(html.contains("hedging-controls"));
        assertTrue(html.contains("view-controls"));
        assertTrue(html.contains("Closed raw"));
        assertTrue(html.contains("CO-corrected"));
        assertTrue(html.contains("Open-loop"));
        assertTrue(html.contains("p99"));
        assertFalse(html.contains("innerHTML"));
    }

    @Test
    void writeReadsQuotedCsvFields(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"post_number\":1,\"title\":\"Quoted\"}");
        Path csv = dir.resolve("quoted.csv");
        Files.writeString(csv, "strategy,note\n\"token-bucket, burst\",\"quote \"\"inside\"\"\"\n");

        Path report = dir.resolve("report.html");
        ReportIndexWriter.write(
                1,
                "Quoted",
                report,
                manifest,
                List.of(new ReportIndexWriter.Series("quoted", csv)));

        String html = Files.readString(report);
        assertTrue(html.contains("\"strategy\":\"token-bucket, burst\""));
        assertTrue(html.contains("\"note\":\"quote \\\"inside\\\"\""));
    }

    @Test
    void writeRejectsMalformedCsv(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"post_number\":1,\"title\":\"Malformed\"}");
        Path csv = dir.resolve("bad.csv");
        Files.writeString(csv, "name\n\"bad\"tail\n");

        Path report = dir.resolve("report.html");
        assertThrows(
                IOException.class,
                () -> ReportIndexWriter.write(
                        1,
                        "Malformed",
                        report,
                        manifest,
                        List.of(new ReportIndexWriter.Series("bad", csv))));
    }

    @Test
    void writeFailsWhenCsvIsMissing(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"post_number\":1,\"title\":\"Missing\"}");

        Path report = dir.resolve("report.html");
        assertThrows(
                IOException.class,
                () -> ReportIndexWriter.write(
                        1,
                        "Missing",
                        report,
                        manifest,
                        List.of(new ReportIndexWriter.Series("missing", dir.resolve("missing.csv")))));
    }
}
