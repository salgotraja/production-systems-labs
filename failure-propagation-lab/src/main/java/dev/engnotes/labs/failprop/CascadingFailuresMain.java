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
import dev.engnotes.labs.failprop.cascade.CascadeRunResult;
import dev.engnotes.labs.failprop.cascade.CascadeScenario;
import dev.engnotes.labs.failprop.cascade.CascadeSweepPoint;
import dev.engnotes.labs.failprop.cascade.CascadeWindowSample;
import dev.engnotes.labs.failprop.charting.CascadeChartGenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the cascading-failures experiment (Series 3, Post 1: "Cascading Failures
 * Explained").
 *
 * <p>Runs two routes through a shared frontend - one route depends on a database, the other
 * never touches it - then degrades the database and shows both routes failing: the cascade is
 * resource coupling (a shared bounded worker pool), not data coupling. Produces:
 * <ul>
 *   <li>a degradation-sweep CSV and a mid-run-degradation timeline CSV (the golden contract);</li>
 *   <li>two PNG charts: the sweep cliff and the timeline of both routes dying;</li>
 *   <li>{@code manifest.json} and a self-contained {@code report.html}.</li>
 * </ul>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :failure-propagation-lab:runCascadingFailures \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/cascading-failures"
 * </pre>
 */
public final class CascadingFailuresMain {

    static final String SWEEP_CSV_HEADER = "db_service_ms,route,success_pct,p99_ms";
    static final String TIMELINE_CSV_HEADER =
            "window_start_ms,route_a_success_pct,route_b_success_pct,"
            + "frontend_queue,service_a_queue,service_b_queue,database_queue";

    private CascadingFailuresMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        CascadeScenario scenario = new CascadeScenario();
        CascadeRunResult result = scenario.run(cliArgs);

        Path sweepCsv = cliArgs.outputDir().resolve("fp-post1-cascade-sweep.csv");
        writeSweepCsv(result.sweep(), sweepCsv);
        Path timelineCsv = cliArgs.outputDir().resolve("fp-post1-cascade-timeline.csv");
        writeTimelineCsv(result.timeline(), timelineCsv);
        System.out.printf("CSV written: %s%n", sweepCsv);
        System.out.printf("CSV written: %s%n%n", timelineCsv);

        printSweepTable(result.sweep());

        System.out.println("Generating charts...");
        CascadeChartGenerator.saveSweepChart(result, cliArgs.outputDir().resolve("fp-post1-cascade-sweep"));
        CascadeChartGenerator.saveTimelineChart(result, cliArgs.outputDir().resolve("fp-post1-cascade-timeline"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.CASCADING_FAILURES,
                cliArgs,
                args,
                PostArtifacts.csv("Degradation sweep", sweepCsv),
                PostArtifacts.csv("Degradation timeline", timelineCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Cascading Failures Explained");
        System.out.println("=================================================");
        System.out.printf("  Window per run: %s%n", args.duration());
        System.out.printf("  Mode:           %s%n", args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:     %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSweepCsv(List<CascadeSweepPoint> sweep, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SWEEP_CSV_HEADER);
            w.newLine();
            for (CascadeSweepPoint point : sweep) {
                w.write(String.format(Locale.ROOT, "%d,%s,%.1f,%.1f",
                        point.dbServiceMs(),
                        point.route(),
                        point.successPct(),
                        point.p99Ms()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writeTimelineCsv(List<CascadeWindowSample> timeline, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(TIMELINE_CSV_HEADER);
            w.newLine();
            for (CascadeWindowSample sample : timeline) {
                w.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%d,%d,%d,%d",
                        sample.windowStartMs(),
                        sample.routeASuccessPct(),
                        sample.routeBSuccessPct(),
                        sample.frontendQueue(),
                        sample.serviceAQueue(),
                        sample.serviceBQueue(),
                        sample.databaseQueue()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printSweepTable(List<CascadeSweepPoint> sweep) {
        System.out.printf("  %-14s  %-9s  %-10s  %-9s%n", "db_service_ms", "route", "success%", "p99(ms)");
        System.out.println("  " + "-".repeat(50));
        for (CascadeSweepPoint p : sweep) {
            String marker = p.route().equals("route-b") && p.successPct() < 50.0
                    ? " <- never touches the database"
                    : "";
            System.out.printf(Locale.ROOT, "  %-14d  %-9s  %-10.1f  %-9.1f%s%n",
                    p.dbServiceMs(), p.route(), p.successPct(), p.p99Ms(), marker);
        }
        System.out.println();
    }

    private static void printSummary(CascadeRunResult result) {
        CascadeSweepPoint worstB = result.sweep().stream()
                .filter(p -> p.route().equals("route-b"))
                .min((a, b) -> Double.compare(a.successPct(), b.successPct()))
                .orElse(null);
        System.out.println("=================================================");
        System.out.println("  Cascade summary");
        System.out.println("=================================================");
        if (worstB != null) {
            System.out.printf(Locale.ROOT,
                    "  route-b worst success: %.1f%% at db service time %d ms%n",
                    worstB.successPct(), worstB.dbServiceMs());
        }
        System.out.println("  Lesson: the cascade is resource coupling, not data coupling -");
        System.out.println("          a shared bounded pool carries the failure to routes that");
        System.out.println("          never touch the slow dependency.");
        System.out.println("  Note:   synthetic lab model; production numbers vary, but the");
        System.out.println("          propagation mechanism is the same.");
        System.out.println("=================================================");
    }
}
