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

import dev.engnotes.labs.backpressure.charting.SloControlChartGenerator;
import dev.engnotes.labs.backpressure.slocontrol.SloControlScenario;
import dev.engnotes.labs.backpressure.slocontrol.SloPointResult;
import dev.engnotes.labs.backpressure.slocontrol.SloRunResult;
import dev.engnotes.labs.backpressure.slocontrol.SloWindowSample;
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
 * Entry point for the SLO-driven load-control experiment (Series 2, Post 5: "Bounded Systems
 * Architecture + SLO-Driven Load Control" - the series capstone).
 *
 * <p>Assembles the bounded system from Posts 2 and 4 (in-system door bound + dequeue expiry) and
 * adds the value dimension: two criticality classes through a class-blind vs criticality-aware
 * door. The SLO is a success-rate SLO - the bounded architecture makes served latency
 * deadline-flat for everyone, so the kill is in the completion rate. Produces two CSVs
 * (protection sweep and burst time series), two PNG charts, plus {@code manifest.json} and
 * {@code report.html}.
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :backpressure-playground:runSloLoadControl \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/slo-load-control"
 * </pre>
 */
public final class SloLoadControlMain {

    static final String PROTECTION_CSV_HEADER =
            "policy,offered_rps,critical_offered_rps,critical_success_pct,background_success_pct,"
                    + "critical_p99_ms,background_p99_ms,critical_slo_met,total_goodput_rps";
    static final String BURST_CSV_HEADER =
            "window_start_ms,blind_critical_pct,priority_critical_pct,"
                    + "blind_background_pct,priority_background_pct";

    private SloLoadControlMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        SloRunResult result = new SloControlScenario().run(cliArgs);
        printBanner(cliArgs, result);

        Path protectionCsv = cliArgs.outputDir().resolve("bp-post5-protection.csv");
        writeProtectionCsv(result.sweep(), protectionCsv);
        Path burstCsv = cliArgs.outputDir().resolve("bp-post5-slo-burst.csv");
        writeBurstCsv(result.windows(), burstCsv);
        System.out.printf("CSV written: %s%n", protectionCsv);
        System.out.printf("CSV written: %s%n%n", burstCsv);

        printProtectionTable(result);

        System.out.println("Generating charts...");
        SloControlChartGenerator.saveBurstChart(result, cliArgs.outputDir().resolve("bp-post5-slo-burst"));
        SloControlChartGenerator.saveProtectionChart(result, cliArgs.outputDir().resolve("bp-post5-protection"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.SLO_LOAD_CONTROL,
                cliArgs,
                args,
                PostArtifacts.csv("Protection sweep", protectionCsv),
                PostArtifacts.csv("Burst time series", burstCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args, SloRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Bounded Systems + SLO-Driven Load Control");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:        %.0f rps%n", result.serverCapacityRps());
        System.out.printf("  Door bound:      %d in system (capacity x deadline)%n", result.queueBound());
        System.out.printf(Locale.ROOT, "  Critical share:  %.0f%%%n", result.criticalSharePct());
        System.out.printf(Locale.ROOT, "  Ceiling:         %.0f rps (capacity / critical share)%n",
                result.protectionCeilingRps());
        System.out.printf(Locale.ROOT, "  SLO:             >= %.0f%% of critical complete in deadline%n",
                result.sloTargetPct());
        System.out.printf("  Window:          %s%n", args.duration());
        System.out.printf("  Output dir:      %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeProtectionCsv(List<SloPointResult> points, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(PROTECTION_CSV_HEADER);
            w.newLine();
            for (SloPointResult p : points) {
                w.write(String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%s,%.1f",
                        p.policy(),
                        p.offeredRps(),
                        p.criticalOfferedRps(),
                        p.criticalSuccessPct(),
                        p.backgroundSuccessPct(),
                        p.criticalP99Ms(),
                        p.backgroundP99Ms(),
                        p.criticalSloMet() ? "yes" : "no",
                        p.totalGoodputRps()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writeBurstCsv(List<SloWindowSample> windows, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(BURST_CSV_HEADER);
            w.newLine();
            for (SloWindowSample s : windows) {
                w.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%.1f",
                        s.windowStartMs(), s.blindCriticalPct(), s.priorityCriticalPct(),
                        s.blindBackgroundPct(), s.priorityBackgroundPct()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printProtectionTable(SloRunResult result) {
        System.out.printf("  %-9s  %-8s  %-9s  %-10s  %-10s  %-9s  %-8s  %-8s%n",
                "policy", "offered", "critical", "crit ok%", "bg ok%", "crit p99", "SLO met", "goodput");
        System.out.println("  " + "-".repeat(84));
        String current = "";
        for (SloPointResult p : result.sweep()) {
            if (!p.policy().equals(current)) {
                if (!current.isEmpty()) {
                    System.out.println();
                }
                current = p.policy();
            }
            System.out.printf(Locale.ROOT,
                    "  %-9s  %-8.1f  %-9.1f  %-10.1f  %-10.1f  %-9.1f  %-8s  %-8.1f%n",
                    p.policy(), p.offeredRps(), p.criticalOfferedRps(), p.criticalSuccessPct(),
                    p.backgroundSuccessPct(), p.criticalP99Ms(),
                    p.criticalSloMet() ? "yes" : "no", p.totalGoodputRps());
        }
        System.out.println();
    }

    private static void printSummary(SloRunResult result) {
        System.out.println("=================================================");
        System.out.println("  SLO-control summary");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:        %.0f rps, ceiling %.0f rps%n",
                result.serverCapacityRps(), result.protectionCeilingRps());
        System.out.println("  Lesson: the bounded system makes served latency deadline-flat for");
        System.out.println("          everyone - a latency SLO cannot tell the policies apart.");
        System.out.println("          The battleground is the success rate: blind shedding lets");
        System.out.println("          the critical class degrade in lockstep with background,");
        System.out.println("          while a criticality-aware door holds the critical SLO at");
        System.out.println("          ~100% until critical traffic alone exceeds capacity. The");
        System.out.println("          background class pays the bill - that is the design choice.");
        System.out.println("  Note:   synthetic lab model; the numbers vary in production, but");
        System.out.println("          the protection mechanism and its ceiling hold.");
        System.out.println("=================================================");
    }
}
