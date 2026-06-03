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

import dev.engnotes.labs.backpressure.charting.CollapseChartGenerator;
import dev.engnotes.labs.backpressure.collapse.CollapseRunResult;
import dev.engnotes.labs.backpressure.collapse.CollapseScenario;
import dev.engnotes.labs.backpressure.collapse.LoadLevelResult;
import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.cli.CliParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the load-collapse experiment (Series 2, Post 1: "Why Systems Collapse
 * Under Load").
 *
 * <p>Sweeps offered load from below to well above a fixed-capacity server, with and without
 * client retries, and shows goodput collapsing below capacity instead of plateauing at it once
 * the server has no admission control. Produces:
 * <ul>
 *   <li>a sweep CSV (the golden-file contract);</li>
 *   <li>two PNG charts: the goodput collapse cliff and the retry-storm amplification;</li>
 *   <li>{@code manifest.json} and a self-contained {@code report.html}.</li>
 * </ul>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :backpressure-playground:runLoadCollapse \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/load-collapse"
 * </pre>
 */
public final class LoadCollapseMain {

    static final String SWEEP_CSV_HEADER =
            "mode,offered_rps,effective_rps,ideal_goodput_rps,goodput_rps,"
            + "wasted_pct,p50_ms,p99_ms,avg_queue_depth";

    private LoadCollapseMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        CollapseScenario scenario = new CollapseScenario();
        CollapseRunResult result = scenario.run(cliArgs);
        System.out.printf(Locale.ROOT, "Server capacity: %.0f rps%n%n", result.serverCapacityRps());

        Path sweepCsv = cliArgs.outputDir().resolve("bp-post1-collapse-sweep.csv");
        writeSweepCsv(result.levels(), sweepCsv);
        System.out.printf("CSV written: %s%n%n", sweepCsv);

        printResultsTable(result);

        System.out.println("Generating charts...");
        CollapseChartGenerator.saveGoodputChart(result, cliArgs.outputDir().resolve("bp-post1-goodput"));
        CollapseChartGenerator.saveAmplificationChart(result, cliArgs.outputDir().resolve("bp-post1-amplification"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.LOAD_COLLAPSE,
                cliArgs,
                args,
                PostArtifacts.csv("Collapse sweep", sweepCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Why Systems Collapse Under Load");
        System.out.println("=================================================");
        System.out.printf("  Window per level: %s%n", args.duration());
        System.out.printf("  Mode:             %s%n", args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:       %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSweepCsv(List<LoadLevelResult> levels, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SWEEP_CSV_HEADER);
            w.newLine();
            for (LoadLevelResult level : levels) {
                w.write(String.format(Locale.ROOT, "%s,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.2f",
                        level.mode(),
                        level.offeredRps(),
                        level.effectiveRps(),
                        level.idealGoodputRps(),
                        level.goodputRps(),
                        level.wastedPct(),
                        level.p50Ms(),
                        level.p99Ms(),
                        level.avgQueueDepth()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printResultsTable(CollapseRunResult result) {
        System.out.printf("  %-9s  %-9s  %-11s  %-9s  %-9s  %-9s  %-9s%n",
                "mode", "offered", "effective", "ideal", "goodput", "wasted%", "p99(ms)");
        System.out.println("  " + "-".repeat(78));
        for (LoadLevelResult l : result.levels()) {
            String marker = l.offeredRps() > result.serverCapacityRps() && l.goodputRps() < l.idealGoodputRps() * 0.5
                    ? " <- collapsed"
                    : "";
            System.out.printf(Locale.ROOT,
                    "  %-9s  %-9d  %-11.1f  %-9.1f  %-9.1f  %-9.1f  %-9.1f%s%n",
                    l.mode(), l.offeredRps(), l.effectiveRps(), l.idealGoodputRps(),
                    l.goodputRps(), l.wastedPct(), l.p99Ms(), marker);
        }
        System.out.println();
    }

    private static void printSummary(CollapseRunResult result) {
        LoadLevelResult worstNoRetry = worstGoodput(result, "no-retry");
        LoadLevelResult worstRetry = worstGoodput(result, "retry");
        System.out.println("=================================================");
        System.out.println("  Collapse summary");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:               %.0f rps%n", result.serverCapacityRps());
        if (worstNoRetry != null) {
            System.out.printf(Locale.ROOT, "  Worst goodput no-retry: %.1f rps at %d rps offered%n",
                    worstNoRetry.goodputRps(), worstNoRetry.offeredRps());
        }
        if (worstRetry != null) {
            System.out.printf(Locale.ROOT, "  Worst goodput retry:    %.1f rps at %d rps offered (%.0f rps effective)%n",
                    worstRetry.goodputRps(), worstRetry.offeredRps(), worstRetry.effectiveRps());
        }
        System.out.println("  Lesson: without admission control, overload collapses goodput below");
        System.out.println("          capacity; retries turn the collapse into a death spiral.");
        System.out.println("  Note:   synthetic lab model; production numbers vary with real traffic,");
        System.out.println("          but the collapse mechanism is the same.");
        System.out.println("=================================================");
    }

    private static LoadLevelResult worstGoodput(CollapseRunResult result, String mode) {
        return result.levels().stream()
                .filter(l -> l.mode().equals(mode))
                .min((a, b) -> Double.compare(a.goodputRps(), b.goodputRps()))
                .orElse(null);
    }
}
