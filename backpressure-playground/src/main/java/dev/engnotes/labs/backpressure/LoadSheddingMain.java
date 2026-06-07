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

import dev.engnotes.labs.backpressure.charting.SheddingChartGenerator;
import dev.engnotes.labs.backpressure.shedding.ShedPointResult;
import dev.engnotes.labs.backpressure.shedding.ShedRunResult;
import dev.engnotes.labs.backpressure.shedding.ShedWindowSample;
import dev.engnotes.labs.backpressure.shedding.SheddingScenario;
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
 * Entry point for the load-shedding experiment (Series 2, Post 4: "Load Shedding Strategies").
 *
 * <p>Compares which work gets served and which gets abandoned under overload: no shedding
 * (fifo), fast-fail at the door (tail-drop), deadline-aware discard at dequeue (expire), and
 * newest-first (lifo). Produces two CSVs (offered-load sweep and burst-hangover time series),
 * two PNG charts, plus {@code manifest.json} and {@code report.html}.
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :backpressure-playground:runLoadShedding \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/load-shedding"
 * </pre>
 */
public final class LoadSheddingMain {

    static final String SWEEP_CSV_HEADER =
            "policy,offered_rps,goodput_rps,shed_pct,served_late_pct,p99_served_ms,shed_wait_p50_ms,wasted_pct";
    static final String HANGOVER_CSV_HEADER =
            "window_start_ms,fifo_p99_ms,tail_drop_p99_ms,expire_p99_ms,lifo_p99_ms";

    private LoadSheddingMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        ShedRunResult result = new SheddingScenario().run(cliArgs);
        printBanner(cliArgs, result);

        Path sweepCsv = cliArgs.outputDir().resolve("bp-post4-shed-sweep.csv");
        writeSweepCsv(result.sweep(), sweepCsv);
        Path hangoverCsv = cliArgs.outputDir().resolve("bp-post4-hangover.csv");
        writeHangoverCsv(result.hangover(), hangoverCsv);
        System.out.printf("CSV written: %s%n", sweepCsv);
        System.out.printf("CSV written: %s%n%n", hangoverCsv);

        printSweepTable(result);

        System.out.println("Generating charts...");
        SheddingChartGenerator.saveHangoverChart(result, cliArgs.outputDir().resolve("bp-post4-hangover"));
        SheddingChartGenerator.saveSweepChart(result, cliArgs.outputDir().resolve("bp-post4-shed-sweep"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.LOAD_SHEDDING,
                cliArgs,
                args,
                PostArtifacts.csv("Shed sweep", sweepCsv),
                PostArtifacts.csv("Burst hangover", hangoverCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args, ShedRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Load Shedding Strategies");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:        %.0f rps%n", result.serverCapacityRps());
        System.out.printf("  Tail-drop bound: %d queued (capacity x deadline)%n", result.queueBound());
        System.out.printf("  Window:          %s%n", args.duration());
        System.out.printf("  Output dir:      %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSweepCsv(List<ShedPointResult> points, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SWEEP_CSV_HEADER);
            w.newLine();
            for (ShedPointResult p : points) {
                w.write(String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f",
                        p.policy(),
                        p.offeredRps(),
                        p.goodputRps(),
                        p.shedPct(),
                        p.servedLatePct(),
                        p.p99ServedMs(),
                        p.shedWaitP50Ms(),
                        p.wastedPct()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writeHangoverCsv(List<ShedWindowSample> windows, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(HANGOVER_CSV_HEADER);
            w.newLine();
            for (ShedWindowSample s : windows) {
                w.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%.1f",
                        s.windowStartMs(), s.fifoP99Ms(), s.tailDropP99Ms(), s.expireP99Ms(), s.lifoP99Ms()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printSweepTable(ShedRunResult result) {
        System.out.printf("  %-10s  %-8s  %-9s  %-7s  %-10s  %-10s  %-12s  %-8s%n",
                "policy", "offered", "goodput", "shed%", "servLate%", "p99 srvd", "shed p50", "wasted%");
        System.out.println("  " + "-".repeat(88));
        String current = "";
        for (ShedPointResult p : result.sweep()) {
            if (!p.policy().equals(current)) {
                if (!current.isEmpty()) {
                    System.out.println();
                }
                current = p.policy();
            }
            System.out.printf(Locale.ROOT,
                    "  %-10s  %-8.1f  %-9.1f  %-7.1f  %-10.1f  %-10.1f  %-12.1f  %-8.1f%n",
                    p.policy(), p.offeredRps(), p.goodputRps(), p.shedPct(),
                    p.servedLatePct(), p.p99ServedMs(), p.shedWaitP50Ms(), p.wastedPct());
        }
        System.out.println();
    }

    private static void printSummary(ShedRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Shedding summary");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:        %.0f rps%n", result.serverCapacityRps());
        System.out.printf("  Tail-drop bound: %d queued (capacity x deadline)%n", result.queueBound());
        System.out.println("  Lesson: under sustained overload every policy that sheds restores");
        System.out.println("          goodput - what they choose is who gets served and how long");
        System.out.println("          the doomed wait. Tail-drop fast-fails at the door, expire");
        System.out.println("          slow-fails at the deadline but needs no knob, lifo serves");
        System.out.println("          fresh work through any backlog and never tells the starved.");
        System.out.println("          After a burst, fifo keeps paying long after the spike ends.");
        System.out.println("  Note:   synthetic lab model; the numbers vary in production, but");
        System.out.println("          the policy fingerprints and the hangover mechanism hold.");
        System.out.println("=================================================");
    }
}
