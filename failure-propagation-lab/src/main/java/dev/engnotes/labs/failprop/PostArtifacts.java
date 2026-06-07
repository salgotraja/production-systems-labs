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

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.manifest.ExperimentManifest;
import dev.engnotes.labs.commons.report.ReportIndexWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Emits the per-run {@code manifest.json} and self-contained {@code report.html} for a
 * failure-propagation-lab experiment. Mirrors the Series 2 helper but resolves golden
 * references through {@link ExperimentDefinition#goldenDir()} (flat {@code golden/fp-post{N}}).
 */
final class PostArtifacts {

    private PostArtifacts() {}

    record CsvSeries(String name, Path path) {}

    static CsvSeries csv(String name, Path path) {
        return new CsvSeries(name, path);
    }

    static void write(
            ExperimentDefinition experiment,
            CliArgs cliArgs,
            String[] rawArgs,
            CsvSeries... series) {

        write(experiment, cliArgs, rawArgs, Arrays.asList(series));
    }

    static void write(
            ExperimentDefinition experiment,
            CliArgs cliArgs,
            String[] rawArgs,
            List<CsvSeries> series) {

        for (CsvSeries item : series) {
            if (!Files.isRegularFile(item.path())) {
                throw new ArtifactWriteException("Missing CSV artifact: " + item.path());
            }
        }

        Path manifest = cliArgs.outputDir().resolve("manifest.json");
        List<ExperimentManifest.CsvArtifact> csvArtifacts = series.stream()
                .map(item -> new ExperimentManifest.CsvArtifact(item.name(), item.path()))
                .toList();
        try {
            ExperimentManifest.write(
                    experiment.postNumber(),
                    experiment.title(),
                    cliArgs,
                    rawArgs,
                    csvArtifacts,
                    manifest,
                    experiment.goldenDir());
        } catch (IOException e) {
            throw new ArtifactWriteException("Failed to write manifest.json: " + manifest, e);
        }

        Path report = cliArgs.outputDir().resolve("report.html");
        List<ReportIndexWriter.Series> reportSeries = series.stream()
                .map(item -> new ReportIndexWriter.Series(item.name(), item.path()))
                .toList();
        try {
            ReportIndexWriter.write(experiment.postNumber(), experiment.title(), report, manifest, reportSeries);
        } catch (IOException e) {
            throw new ArtifactWriteException("Failed to write report.html: " + report, e);
        }

        System.out.printf("Manifest written: %s%n", manifest);
        System.out.printf("Report written:   %s%n%n", report);
    }
}
