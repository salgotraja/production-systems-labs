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

import dev.engnotes.labs.backpressure.charting.ShapingChartGenerator;
import dev.engnotes.labs.backpressure.shaping.ShapingPointResult;
import dev.engnotes.labs.backpressure.shaping.ShapingRunResult;
import dev.engnotes.labs.backpressure.shaping.ShapingScenario;
import dev.engnotes.labs.backpressure.shaping.ShapingWindowSample;
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
 * Entry point for the token-bucket vs leaky-bucket experiment (Series 2, Post 3: "Token Bucket
 * vs Leaky Bucket").
 *
 * <p>Both gates bound the same average rate; the design difference is where the burst and the
 * wait land - the token bucket passes bursts downstream (policing), the leaky bucket flattens
 * them into gate delay (shaping). Produces two CSVs (burst sweep and downstream-rate time
 * series), two PNG charts, plus {@code manifest.json} and {@code report.html}.
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :backpressure-playground:runTokenVsLeaky \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/token-vs-leaky"
 * </pre>
 */
public final class TokenVsLeakyMain {

    static final String BURST_SWEEP_CSV_HEADER =
            "limiter,burst_capacity,offered_rps,goodput_rps,reject_pct,served_late_pct,"
                    + "gate_delay_p99_ms,server_wait_p99_ms,downstream_peak_rps";
    static final String SHAPING_CSV_HEADER = "window_start_ms,offered_rps,token_bucket_rps,leaky_bucket_rps";

    private TokenVsLeakyMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        ShapingRunResult result = new ShapingScenario().run(cliArgs);
        printBanner(cliArgs, result);

        Path sweepCsv = cliArgs.outputDir().resolve("bp-post3-burst-sweep.csv");
        writeBurstSweepCsv(result, sweepCsv);
        Path shapingCsv = cliArgs.outputDir().resolve("bp-post3-shaping.csv");
        writeShapingCsv(result.windows(), shapingCsv);
        System.out.printf("CSV written: %s%n", sweepCsv);
        System.out.printf("CSV written: %s%n%n", shapingCsv);

        printBurstSweepTable(result);

        System.out.println("Generating charts...");
        ShapingChartGenerator.saveShapingChart(result, cliArgs.outputDir().resolve("bp-post3-shaping"));
        ShapingChartGenerator.saveBurstSweepChart(result, cliArgs.outputDir().resolve("bp-post3-burst-sweep"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.TOKEN_VS_LEAKY,
                cliArgs,
                args,
                PostArtifacts.csv("Burst sweep", sweepCsv),
                PostArtifacts.csv("Downstream rate", shapingCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args, ShapingRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Token Bucket vs Leaky Bucket");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:        %.0f rps%n", result.serverCapacityRps());
        System.out.printf(Locale.ROOT, "  Gate rate:       %.0f rps (both gates)%n", result.gateRateRps());
        System.out.printf("  Deadline budget: %d (capacity x deadline)%n", result.burstSweetSpot());
        System.out.printf("  Window:          %s%n", args.duration());
        System.out.printf("  Output dir:      %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeBurstSweepCsv(ShapingRunResult result, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(BURST_SWEEP_CSV_HEADER);
            w.newLine();
            writeSweepRows(w, result.tokenSweep());
            writeSweepRows(w, result.leakySweep());
            w.flush();
        }
    }

    private static void writeSweepRows(BufferedWriter w, List<ShapingPointResult> points) throws IOException {
        for (ShapingPointResult p : points) {
            w.write(String.format(Locale.ROOT, "%s,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f",
                    p.limiter(),
                    p.burstCapacity(),
                    p.offeredRps(),
                    p.goodputRps(),
                    p.rejectPct(),
                    p.servedLatePct(),
                    p.gateDelayP99Ms(),
                    p.serverWaitP99Ms(),
                    p.downstreamPeakRps()));
            w.newLine();
        }
    }

    private static void writeShapingCsv(List<ShapingWindowSample> windows, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SHAPING_CSV_HEADER);
            w.newLine();
            for (ShapingWindowSample s : windows) {
                w.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f",
                        s.windowStartMs(), s.offeredRps(), s.tokenBucketRps(), s.leakyBucketRps()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printBurstSweepTable(ShapingRunResult result) {
        System.out.printf("  %-13s  %-6s  %-9s  %-9s  %-10s  %-10s  %-10s  %-9s%n",
                "limiter", "burst", "goodput", "reject%", "servLate%", "gate p99", "srv p99", "peak rps");
        System.out.println("  " + "-".repeat(90));
        printSweepRows(result.tokenSweep(), result.burstSweetSpot());
        System.out.println();
        printSweepRows(result.leakySweep(), result.burstSweetSpot());
        System.out.println();
    }

    private static void printSweepRows(List<ShapingPointResult> points, int sweetSpot) {
        for (ShapingPointResult p : points) {
            String marker = p.burstCapacity() == sweetSpot ? " <- deadline budget" : "";
            System.out.printf(Locale.ROOT,
                    "  %-13s  %-6d  %-9.1f  %-9.1f  %-10.1f  %-10.1f  %-10.1f  %-9.1f%s%n",
                    p.limiter(), p.burstCapacity(), p.goodputRps(), p.rejectPct(),
                    p.servedLatePct(), p.gateDelayP99Ms(), p.serverWaitP99Ms(),
                    p.downstreamPeakRps(), marker);
        }
    }

    private static void printSummary(ShapingRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Shaping summary");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:        %.0f rps, both gates at %.0f rps sustained%n",
                result.serverCapacityRps(), result.gateRateRps());
        System.out.printf("  Deadline budget: %d (capacity x deadline)%n", result.burstSweetSpot());
        System.out.println("  Lesson: both gates bound the same average rate - goodput cannot tell");
        System.out.println("          them apart. The design choice is where the burst lands: the");
        System.out.println("          token bucket passes it downstream (wait at the server), the");
        System.out.println("          leaky bucket flattens it (wait at the gate). Oversize either");
        System.out.println("          knob and the deadline is blown on that gate's own side.");
        System.out.println("  Note:   synthetic lab model; the numbers vary in production, but the");
        System.out.println("          policing-vs-shaping tradeoff and the deadline budget hold.");
        System.out.println("=================================================");
    }
}
