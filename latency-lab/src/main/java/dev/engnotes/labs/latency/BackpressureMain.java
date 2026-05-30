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

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.cli.CliParser;
import dev.engnotes.labs.latency.backpressure.BackpressureRunResult;
import dev.engnotes.labs.latency.backpressure.BackpressureScenario;
import dev.engnotes.labs.latency.backpressure.BackpressureSnapshot;
import dev.engnotes.labs.latency.backpressure.BackpressureStrategy;
import dev.engnotes.labs.latency.backpressure.BackpressureSummary;
import dev.engnotes.labs.latency.charting.BackpressureChartGenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class BackpressureMain {

    static final String SUMMARY_HEADER =
            "strategy,incoming_requests,accepted_requests,rejected_requests,acceptance_pct,p50_ms,p99_ms,max_buffered";
    static final String SNAPSHOT_HEADER =
            "strategy,elapsed_s,accepted,rejected,throughput_rps,p50_ms,p99_ms,max_buffered";

    private BackpressureMain() {}

    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);
        List<BackpressureStrategy> strategies =
                BackpressureStrategy.parseSelection(cliArgs.extra("backpressure-strategy"));

        printBanner(cliArgs, strategies);

        BackpressureRunResult result = new BackpressureScenario()
                .run(cliArgs.duration().toSeconds(), cliArgs.concurrency(), strategies);

        Path summaryCsv = cliArgs.outputDir().resolve("post5-backpressure-summary.csv");
        writeSummaryCsv(result.summaries(), summaryCsv);
        Path snapshotsCsv = cliArgs.outputDir().resolve("post5-backpressure-snapshots.csv");
        writeSnapshotsCsv(result.snapshots(), snapshotsCsv);

        BackpressureChartGenerator.saveAcceptanceChart(
                result.summaries(),
                cliArgs.outputDir().resolve("post5-acceptance"));
        BackpressureChartGenerator.saveLatencyChart(
                result.summaries(),
                cliArgs.outputDir().resolve("post5-latency"));

        System.out.printf("CSV written: %s%n", summaryCsv);
        System.out.printf("CSV written: %s%n", snapshotsCsv);
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.BACKPRESSURE,
                cliArgs,
                args,
                PostArtifacts.csv("Summary", summaryCsv),
                PostArtifacts.csv("Snapshots", snapshotsCsv));

        printSummary(result);
    }

    private static void printBanner(CliArgs args, List<BackpressureStrategy> strategies) {
        System.out.println("=================================================");
        System.out.println("  Backpressure Strategy Comparison");
        System.out.println("=================================================");
        System.out.printf("  Duration:       %s%n", args.duration());
        System.out.printf("  Incoming rate:  %d rps%n", args.concurrency() * 20);
        System.out.printf("  Strategies:     %s%n",
                strategies.stream().map(BackpressureStrategy::label).toList());
        System.out.printf("  Output dir:     %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSummaryCsv(List<BackpressureSummary> summaries, Path csvPath)
            throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(SUMMARY_HEADER);
            writer.newLine();
            for (BackpressureSummary summary : summaries) {
                writer.write(String.format("%s,%d,%d,%d,%.1f,%.1f,%.1f,%d",
                        summary.strategy().label(),
                        summary.incomingRequests(),
                        summary.acceptedRequests(),
                        summary.rejectedRequests(),
                        summary.acceptancePct(),
                        summary.p50Ms(),
                        summary.p99Ms(),
                        summary.maxBuffered()));
                writer.newLine();
            }
            writer.flush();
        }
    }

    private static void writeSnapshotsCsv(List<BackpressureSnapshot> snapshots, Path csvPath)
            throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(SNAPSHOT_HEADER);
            writer.newLine();
            for (BackpressureSnapshot snapshot : snapshots) {
                writer.write(String.format("%s,%d,%d,%d,%.1f,%.1f,%.1f,%d",
                        snapshot.strategy().label(),
                        snapshot.elapsedSeconds(),
                        snapshot.accepted(),
                        snapshot.rejected(),
                        snapshot.throughputRps(),
                        snapshot.p50Ms(),
                        snapshot.p99Ms(),
                        snapshot.maxBuffered()));
                writer.newLine();
            }
            writer.flush();
        }
    }

    private static void printSummary(BackpressureRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Backpressure Summary");
        System.out.println("=================================================");
        System.out.printf("  Incoming rate: %d rps | Target admit rate: %d rps | Duration: %ds%n%n",
                result.incomingRps(),
                result.admittedRps(),
                result.durationSeconds());
        System.out.printf("  %-28s  %-8s  %-8s  %-8s  %-8s  %-8s%n",
                "strategy", "accepted", "rejected", "accept%", "p99(ms)", "buffer");
        System.out.println("  " + "-".repeat(82));
        for (BackpressureSummary summary : result.summaries()) {
            System.out.printf("  %-28s  %-8d  %-8d  %-8.1f  %-8.1f  %-8d%n",
                    summary.strategy().label(),
                    summary.acceptedRequests(),
                    summary.rejectedRequests(),
                    summary.acceptancePct(),
                    summary.p99Ms(),
                    summary.maxBuffered());
        }
        System.out.println("=================================================");
    }
}
