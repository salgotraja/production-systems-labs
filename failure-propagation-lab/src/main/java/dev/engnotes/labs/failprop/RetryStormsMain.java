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
import dev.engnotes.labs.commons.cli.CliParser;
import dev.engnotes.labs.failprop.charting.StormChartGenerator;
import dev.engnotes.labs.failprop.retrystorm.RetryStormScenario;
import dev.engnotes.labs.failprop.retrystorm.StormRunResult;
import dev.engnotes.labs.failprop.retrystorm.StormSweepPoint;
import dev.engnotes.labs.failprop.retrystorm.StormWindowSample;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the retry-storms experiment (Series 3, Post 2: "Retry Storms and
 * Amplification").
 *
 * <p>Runs a three-service chain whose two non-leaf hops each apply a naive timeout-and-retry
 * policy, against a healthy and a hard-down database, and shows retries compounding
 * multiplicatively across hops: R attempts per hop becomes up to R^2 attempts at the bottom,
 * buying load instead of goodput. Produces:
 * <ul>
 *   <li>an amplification-sweep CSV and a storm timeline CSV (the golden contract);</li>
 *   <li>two PNG charts: the storm's attempt-rate spike with its decay tail, and the R^2
 *       amplification curve;</li>
 *   <li>{@code manifest.json} and a self-contained {@code report.html}.</li>
 * </ul>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :failure-propagation-lab:runRetryStorms \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/retry-storms"
 * </pre>
 */
public final class RetryStormsMain {

    static final String SWEEP_CSV_HEADER =
            "mode,attempts_per_hop,success_pct,db_attempts_per_request,db_attempts_rps,p99_resolution_ms";
    static final String TIMELINE_CSV_HEADER =
            "window_start_ms,r1_success_pct,r3_success_pct,r1_db_attempts_rps,r3_db_attempts_rps";

    private RetryStormsMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        RetryStormScenario scenario = new RetryStormScenario();
        StormRunResult result = scenario.run(cliArgs);

        Path sweepCsv = cliArgs.outputDir().resolve("fp-post2-amplification-sweep.csv");
        writeSweepCsv(result.sweep(), sweepCsv);
        Path timelineCsv = cliArgs.outputDir().resolve("fp-post2-storm-timeline.csv");
        writeTimelineCsv(result.timeline(), timelineCsv);
        System.out.printf("CSV written: %s%n", sweepCsv);
        System.out.printf("CSV written: %s%n%n", timelineCsv);

        printSweepTable(result.sweep());

        System.out.println("Generating charts...");
        StormChartGenerator.saveStormTimelineChart(result, cliArgs.outputDir().resolve("fp-post2-storm-timeline"));
        StormChartGenerator.saveRescueChart(result, cliArgs.outputDir().resolve("fp-post2-rescue"));
        StormChartGenerator.saveAmplificationChart(result, cliArgs.outputDir().resolve("fp-post2-amplification"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.RETRY_STORMS,
                cliArgs,
                args,
                PostArtifacts.csv("Amplification sweep", sweepCsv),
                PostArtifacts.csv("Storm timeline", timelineCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Retry Storms and Amplification");
        System.out.println("=================================================");
        System.out.printf("  Window per run: %s%n", args.duration());
        System.out.printf("  Mode:           %s%n", args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:     %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSweepCsv(List<StormSweepPoint> sweep, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SWEEP_CSV_HEADER);
            w.newLine();
            for (StormSweepPoint point : sweep) {
                w.write(String.format(Locale.ROOT, "%s,%d,%.1f,%.2f,%.1f,%.1f",
                        point.mode(),
                        point.attemptsPerHop(),
                        point.successPct(),
                        point.dbAttemptsPerRequest(),
                        point.dbAttemptsRps(),
                        point.p99ResolutionMs()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writeTimelineCsv(List<StormWindowSample> timeline, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(TIMELINE_CSV_HEADER);
            w.newLine();
            for (StormWindowSample sample : timeline) {
                w.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%.1f",
                        sample.windowStartMs(),
                        sample.r1SuccessPct(),
                        sample.r3SuccessPct(),
                        sample.r1DbAttemptsRps(),
                        sample.r3DbAttemptsRps()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printSweepTable(List<StormSweepPoint> sweep) {
        System.out.printf("  %-9s  %-9s  %-10s  %-13s  %-12s  %-9s%n",
                "mode", "attempts", "success%", "db_att/req", "db_att_rps", "p99(ms)");
        System.out.println("  " + "-".repeat(72));
        for (StormSweepPoint p : sweep) {
            String marker = p.mode().equals("degraded") && p.dbAttemptsPerRequest() > p.attemptsPerHop()
                    ? " <- compounding"
                    : "";
            System.out.printf(Locale.ROOT, "  %-9s  %-9d  %-10.1f  %-13.2f  %-12.1f  %-9.1f%s%n",
                    p.mode(), p.attemptsPerHop(), p.successPct(), p.dbAttemptsPerRequest(),
                    p.dbAttemptsRps(), p.p99ResolutionMs(), marker);
        }
        System.out.println();
    }

    private static void printSummary(StormRunResult result) {
        StormSweepPoint worst = result.sweep().stream()
                .filter(p -> p.mode().equals("degraded"))
                .max((a, b) -> Double.compare(a.dbAttemptsPerRequest(), b.dbAttemptsPerRequest()))
                .orElse(null);
        System.out.println("=================================================");
        System.out.println("  Storm summary");
        System.out.println("=================================================");
        if (worst != null) {
            System.out.printf(Locale.ROOT,
                    "  Worst amplification: %.2f db attempts per request at %d attempts per hop"
                    + " (success %.1f%%)%n",
                    worst.dbAttemptsPerRequest(), worst.attemptsPerHop(), worst.successPct());
        }
        System.out.println("  Lesson: per-hop retries compound multiplicatively - R attempts at");
        System.out.println("          each of d hops is R^d at the bottom, and against a hard-down");
        System.out.println("          dependency it buys load, not goodput.");
        System.out.println("  Note:   synthetic lab model; production numbers vary, but the");
        System.out.println("          compounding arithmetic is the same.");
        System.out.println("=================================================");
    }
}
