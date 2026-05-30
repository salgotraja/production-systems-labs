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
import dev.engnotes.labs.latency.charting.HedgingChartGenerator;
import dev.engnotes.labs.latency.hedging.HedgeCostPoint;
import dev.engnotes.labs.latency.hedging.HedgeThreshold;
import dev.engnotes.labs.latency.hedging.HedgingRunResult;
import dev.engnotes.labs.latency.hedging.HedgingScenario;
import dev.engnotes.labs.latency.hedging.HedgingScenarioResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

public final class HedgedRequestsMain {

    static final String COST_CSV_HEADER =
            "threshold,threshold_ms,baseline_p99_ms,hedged_p99_ms,p99_improvement_pct,"
            + "extra_load_pct,hedged_requests,total_requests,wasted_work_ms,wasted_completions";

    private HedgedRequestsMain() {}

    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);
        HedgeThreshold threshold = HedgeThreshold.parse(cliArgs.extra("hedge-threshold"));

        printBanner(cliArgs, threshold);

        HedgingScenario scenario = new HedgingScenario(cliArgs.isDeterministic());
        HedgingScenarioResult result = scenario.run(cliArgs, threshold);

        Path baselineCsv = cliArgs.outputDir().resolve("post3-baseline.csv");
        writeSnapshots(result.baseline().snapshots(), baselineCsv);

        Path hedgedCsv = cliArgs.outputDir().resolve("post3-hedged-" + threshold.label() + ".csv");
        writeSnapshots(result.selectedHedged().snapshots(), hedgedCsv);

        Path costCsv = cliArgs.outputDir().resolve("post3-cost-table.csv");
        writeCostCsv(result.costPoints(), costCsv);

        System.out.printf("CSV written: %s%n", baselineCsv);
        System.out.printf("CSV written: %s%n", hedgedCsv);
        System.out.printf("CSV written: %s%n%n", costCsv);

        System.out.println("Generating charts...");
        HedgingChartGenerator.saveLatencyComparisonChart(
                result.baseline().snapshots(),
                result.selectedHedged().snapshots(),
                threshold.label(),
                cliArgs.outputDir().resolve("post3-latency-comparison"));
        HedgingChartGenerator.saveCostChart(
                result.costPoints(),
                cliArgs.outputDir().resolve("post3-cost-table"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.HEDGED_REQUESTS,
                cliArgs,
                args,
                PostArtifacts.csv("Baseline", baselineCsv),
                PostArtifacts.csv("Hedged " + threshold.label(), hedgedCsv),
                PostArtifacts.csv("Cost table", costCsv));

        printCostTable(result.costPoints());
        printSelectedSummary(result.selectedThreshold(), result.baseline(), result.selectedHedged());
    }

    private static void printBanner(CliArgs args, HedgeThreshold threshold) {
        System.out.println("=================================================");
        System.out.println("  Hedged Requests and Speculative Execution");
        System.out.println("=================================================");
        System.out.printf("  Duration:        %s%n", args.duration());
        System.out.printf("  Request rate:    %d synthetic requests/sec%n", args.concurrency() * 100);
        System.out.printf("  Selected hedge:  %s%n", threshold.label());
        System.out.printf("  Mode:            %s%n", args.isDeterministic() ? "deterministic" : "random-synthetic");
        System.out.printf("  Output dir:      %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSnapshots(List<PercentileSnapshot> snapshots, Path csvPath)
            throws IOException {
        try (CsvSnapshotWriter writer = CsvSnapshotWriter.open(csvPath)) {
            for (PercentileSnapshot snap : snapshots) {
                writer.write(snap);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void writeCostCsv(List<HedgeCostPoint> points, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(COST_CSV_HEADER);
            writer.newLine();
            for (HedgeCostPoint point : points) {
                writer.write(String.format(Locale.ROOT, "%s,%d,%.1f,%.1f,%.1f,%.1f,%d,%d,%d,%d",
                        point.threshold().label(),
                        point.thresholdMs(),
                        point.baselineP99Ms(),
                        point.hedgedP99Ms(),
                        point.p99ImprovementPct(),
                        point.extraLoadPct(),
                        point.hedgeRequests(),
                        point.totalRequests(),
                        point.wastedWorkMs(),
                        point.wastedCompletions()));
                writer.newLine();
            }
            writer.flush();
        }
    }

    private static void printCostTable(List<HedgeCostPoint> points) {
        System.out.printf("  %-10s  %-12s  %-12s  %-12s  %-10s  %-12s%n",
                "threshold", "delay(ms)", "p99 before", "p99 after", "load +%", "p99 gain %");
        System.out.println("  " + "-".repeat(78));
        for (HedgeCostPoint point : points) {
            System.out.printf("  %-10s  %-12d  %-12.1f  %-12.1f  %-10.1f  %-12.1f%n",
                    point.threshold().label(),
                    point.thresholdMs(),
                    point.baselineP99Ms(),
                    point.hedgedP99Ms(),
                    point.extraLoadPct(),
                    point.p99ImprovementPct());
        }
        System.out.println();
    }

    private static void printSelectedSummary(
            HedgeThreshold threshold,
            HedgingRunResult baseline,
            HedgingRunResult hedged) {

        System.out.println("=================================================");
        System.out.println("  Selected Hedge Summary");
        System.out.println("=================================================");
        System.out.printf("  Threshold:           %s%n", threshold.label());
        System.out.printf("  Baseline p99:        %.1fms%n", baseline.p99Ms());
        System.out.printf("  Hedged p99:          %.1fms%n", hedged.p99Ms());
        System.out.printf("  Extra load:          %.1f%%%n", hedged.extraLoadPct());
        System.out.printf("  Hedged requests:     %d / %d%n", hedged.hedgeRequests(), hedged.totalRequests());
        System.out.printf("  Wasted work:         %dms%n", hedged.wastedWorkMs());
        System.out.println("=================================================");
    }
}
