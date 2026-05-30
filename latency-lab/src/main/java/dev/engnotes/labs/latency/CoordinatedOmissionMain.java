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
import dev.engnotes.labs.commons.csv.CsvSnapshotWriter;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import dev.engnotes.labs.latency.charting.CoordinatedOmissionChartGenerator;
import dev.engnotes.labs.latency.measurement.CoordinatedOmissionDemo;
import dev.engnotes.labs.latency.measurement.CoordinatedOmissionResult;
import dev.engnotes.labs.latency.measurement.CoordinatedOmissionRun;
import dev.engnotes.labs.latency.measurement.MeasurementMode;
import dev.engnotes.labs.latency.measurement.PauseServiceModel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CoordinatedOmissionMain {

    static final String SUMMARY_CSV_HEADER = "series,total_requests,whole_run_p99_ms";

    private CoordinatedOmissionMain() {}

    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);
        MeasurementMode mode = MeasurementMode.parse(cliArgs.extra("measurement-mode"));

        printBanner(cliArgs, mode);

        CoordinatedOmissionResult result = new CoordinatedOmissionDemo().run(cliArgs, mode);

        List<PostArtifacts.CsvSeries> csvSeries = new ArrayList<>();
        if (mode.includesClosedLoop()) {
            Path rawCsv = cliArgs.outputDir().resolve("post4-closed-loop-raw.csv");
            writeSnapshots(result.closedLoopRaw().snapshots(), rawCsv);
            csvSeries.add(PostArtifacts.csv("Closed-loop raw", rawCsv));

            Path correctedCsv = cliArgs.outputDir().resolve("post4-closed-loop-corrected.csv");
            writeSnapshots(result.closedLoopCorrected().snapshots(), correctedCsv);
            csvSeries.add(PostArtifacts.csv("Closed-loop corrected", correctedCsv));
        }
        if (mode.includesOpenLoop()) {
            Path openLoopCsv = cliArgs.outputDir().resolve("post4-open-loop.csv");
            writeSnapshots(result.openLoop().snapshots(), openLoopCsv);
            csvSeries.add(PostArtifacts.csv("Open-loop", openLoopCsv));
        }

        Path summaryCsv = cliArgs.outputDir().resolve("post4-summary.csv");
        writeSummary(result, mode, summaryCsv);
        csvSeries.add(PostArtifacts.csv("Summary", summaryCsv));

        CoordinatedOmissionChartGenerator.saveP99ComparisonChart(
                result.closedLoopRaw().snapshots(),
                result.closedLoopCorrected().snapshots(),
                result.openLoop().snapshots(),
                cliArgs.outputDir().resolve("post4-p99-comparison"));
        CoordinatedOmissionChartGenerator.saveThroughputChart(
                result.closedLoopRaw().snapshots(),
                result.openLoop().snapshots(),
                cliArgs.outputDir().resolve("post4-throughput"));

        PostArtifacts.write(
                ExperimentRegistry.COORDINATED_OMISSION,
                cliArgs,
                args,
                csvSeries);

        printSummary(result, mode);
    }

    private static void printBanner(CliArgs args, MeasurementMode mode) {
        System.out.println("=================================================");
        System.out.println("  Coordinated Omission Measurement");
        System.out.println("=================================================");
        System.out.printf("  Duration:          %s%n", args.duration());
        System.out.printf("  Target rate:       %d rps%n", args.concurrency() * 10);
        System.out.printf("  Measurement mode:  %s%n", mode.label());
        System.out.printf("  Output dir:        %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSnapshots(List<PercentileSnapshot> snapshots, Path csvPath)
            throws IOException {
        try (CsvSnapshotWriter writer = CsvSnapshotWriter.open(csvPath)) {
            for (PercentileSnapshot snapshot : snapshots) {
                writer.write(snapshot);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        System.out.printf("CSV written: %s%n", csvPath);
    }

    private static void writeSummary(
            CoordinatedOmissionResult result,
            MeasurementMode mode,
            Path csvPath) throws IOException {

        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(SUMMARY_CSV_HEADER);
            writer.newLine();
            if (mode.includesClosedLoop()) {
                writeSummaryRow(writer, "closed-loop-raw", result.closedLoopRaw());
                writeSummaryRow(writer, "closed-loop-corrected", result.closedLoopCorrected());
            }
            if (mode.includesOpenLoop()) {
                writeSummaryRow(writer, "open-loop", result.openLoop());
            }
            writer.flush();
        }
        System.out.printf("CSV written: %s%n", csvPath);
    }

    private static void writeSummaryRow(BufferedWriter writer, String name, CoordinatedOmissionRun run)
            throws IOException {
        writer.write(String.format(Locale.ROOT, "%s,%d,%.1f",
                name,
                run.totalRequests(),
                run.wholeRunP99Ms()));
        writer.newLine();
    }

    private static void printSummary(CoordinatedOmissionResult result, MeasurementMode mode) {
        PauseServiceModel model = result.serviceModel();
        System.out.println();
        System.out.println("=================================================");
        System.out.println("  Measurement Summary");
        System.out.println("=================================================");
        System.out.printf("  Expected interval:  %dms%n", result.expectedIntervalMs());
        System.out.printf("  Service latency:    %dms baseline%n", model.baseLatencyMs());
        System.out.printf("  Pause window:       %dms..%dms%n",
                model.pauseStartMs(),
                model.pauseStartMs() + model.pauseDurationMs());
        System.out.println();
        if (mode.includesClosedLoop()) {
            System.out.printf("  %-24s requests=%4d  whole-run p99=%6.1fms%n",
                    "Closed-loop raw:",
                    result.closedLoopRaw().totalRequests(),
                    result.closedLoopRaw().wholeRunP99Ms());
            System.out.printf("  %-24s samples=%6d  whole-run p99=%6.1fms%n",
                    "Closed-loop corrected:",
                    correctedSampleCount(result.closedLoopCorrected()),
                    result.closedLoopCorrected().wholeRunP99Ms());
        }
        if (mode.includesOpenLoop()) {
            System.out.printf("  %-24s requests=%4d  whole-run p99=%6.1fms%n",
                    "Open-loop:",
                    result.openLoop().totalRequests(),
                    result.openLoop().wholeRunP99Ms());
        }
        System.out.println("=================================================");
    }

    private static long correctedSampleCount(CoordinatedOmissionRun run) {
        if (run.snapshots().isEmpty()) {
            return 0L;
        }
        return run.snapshots().get(run.snapshots().size() - 1).totalRequests();
    }
}
