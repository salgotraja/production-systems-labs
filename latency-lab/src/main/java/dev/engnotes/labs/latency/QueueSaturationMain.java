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
import dev.engnotes.labs.latency.charting.QueueingChartGenerator;
import dev.engnotes.labs.latency.queueing.LittlesLawResult;
import dev.engnotes.labs.latency.queueing.SaturationPoint;
import dev.engnotes.labs.latency.queueing.SaturationScenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Entry point for the queue-saturation experiment.
 *
 * <p>Runs a saturation sweep across utilization levels from ρ = 0.1 to 1.3 and produces:
 * <ul>
 *   <li>A saturation summary CSV showing throughput, latency, queue depth, and
 *       Little's Law validation at each utilization level</li>
 *   <li>Three PNG charts: knee-of-the-curve, latency blowup, Little's Law overlay</li>
 *   <li>A terminal table showing the complete results including λW vs measured L</li>
 * </ul>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :latency-lab:runQueueSaturation -Pargs="--deterministic --duration 3s --concurrency 1 --output-dir ./results/queue-saturation"
 * </pre>
 */
public final class QueueSaturationMain {

    /** Fixed service time per request; determines server capacity μ = 1000 / SERVICE_TIME_MS rps. */
    private static final long SERVICE_TIME_MS = 10L;

    /** CSV header for the saturation sweep output file. */
    static final String SATURATION_CSV_HEADER =
            "utilization,target_rps,actual_rps,p50_ms,p99_ms,p999_ms,"
            + "mean_sojourn_ms,avg_queue_depth,rejection_count,"
            + "littles_law_computed_l,littles_law_measured_l,littles_law_error_pct";

    private QueueSaturationMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        // --- Run saturation sweep ---
        System.out.println("Running saturation sweep (ρ = 0.1 → 1.3)...");
        System.out.printf("  Service time: %dms | Capacity: %.0f rps%n%n",
                SERVICE_TIME_MS,
                1000.0 / SERVICE_TIME_MS);

        SaturationScenario scenario = new SaturationScenario(SERVICE_TIME_MS, cliArgs.isDeterministic());
        List<SaturationPoint> points = scenario.run(cliArgs);

        // --- Write saturation CSV ---
        Path saturationCsv = cliArgs.outputDir().resolve("post2-saturation.csv");
        writeSaturationCsv(points, saturationCsv);
        System.out.printf("CSV written: %s%n%n", saturationCsv);

        // --- Print results table ---
        printResultsTable(points);

        // --- Generate charts ---
        System.out.println("Generating charts...");
        Path throughputChart = cliArgs.outputDir().resolve("post2-throughput");
        QueueingChartGenerator.saveThroughputChart(points, throughputChart);

        Path latencyChart = cliArgs.outputDir().resolve("post2-latency");
        QueueingChartGenerator.saveLatencyChart(points, latencyChart);

        Path littlesLawChart = cliArgs.outputDir().resolve("post2-littles-law");
        QueueingChartGenerator.saveLittlesLawChart(points, littlesLawChart);
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.QUEUE_SATURATION,
                cliArgs,
                args,
                PostArtifacts.csv("Saturation sweep", saturationCsv));

        // --- Print Little's Law verification summary ---
        printLittlesLawSummary(points);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Queue Saturation and Little's Law");
        System.out.println("=================================================");
        System.out.printf("  Duration per level: %s%n", args.duration());
        System.out.printf("  Mode:               %s%n",
                args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:         %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSaturationCsv(List<SaturationPoint> points, Path csvPath)
            throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SATURATION_CSV_HEADER);
            w.newLine();
            for (SaturationPoint p : points) {
                LittlesLawResult ll = p.littlesLaw();
                w.write(String.format("%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.2f,%d,%.3f,%.3f,%.1f",
                        p.targetUtilization(),
                        p.targetRps(),
                        p.actualThroughputRps(),
                        p.p50Ms(),
                        p.p99Ms(),
                        p.p999Ms(),
                        p.meanSojournMs(),
                        p.avgQueueDepth(),
                        p.rejectionCount(),
                        ll.computedL(),
                        ll.measuredL(),
                        ll.relativeError() * 100.0));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printResultsTable(List<SaturationPoint> points) {
        System.out.printf("  %-6s  %-9s  %-9s  %-9s  %-9s  %-10s  %-10s  %-10s%n",
                "ρ", "targetRps", "actualRps", "p50(ms)", "p99(ms)",
                "queueDepth", "rejected", "utilPct");
        System.out.println("  " + "-".repeat(88));

        for (SaturationPoint p : points) {
            double utilPct = p.targetUtilization() * 100.0;
            // Mark the knee (ρ ≥ 0.7) and saturation (ρ ≥ 1.0)
            String marker = p.targetUtilization() >= 1.0 ? " ← SATURATED"
                    : p.targetUtilization() >= 0.7 ? " ← knee"
                    : "";
            System.out.printf("  %-6.2f  %-9.1f  %-9.1f  %-9.1f  %-9.1f  %-10.2f  %-10d  %-6.0f%%%s%n",
                    p.targetUtilization(),
                    p.targetRps(),
                    p.actualThroughputRps(),
                    p.p50Ms(),
                    p.p99Ms(),
                    p.avgQueueDepth(),
                    p.rejectionCount(),
                    utilPct,
                    marker);
        }
        System.out.println();
    }

    private static void printLittlesLawSummary(List<SaturationPoint> points) {
        System.out.println("=================================================");
        System.out.println("  Little's Law Verification: L = λW");
        System.out.println("=================================================");
        System.out.printf("  %-6s  %-10s  %-12s  %-12s  %-12s  %-10s%n",
                "ρ", "λ (rps)", "W (ms)", "computed L", "measured L", "error %");
        System.out.println("  " + "-".repeat(72));

        for (SaturationPoint p : points) {
            LittlesLawResult ll = p.littlesLaw();
            System.out.printf("  %-6.2f  %-10.1f  %-12.1f  %-12.3f  %-12.3f  %-8.1f%%%n",
                    p.targetUtilization(),
                    ll.lambdaRps(),
                    ll.meanSojournMs(),
                    ll.computedL(),
                    ll.measuredL(),
                    ll.relativeError() * 100.0);
        }
        System.out.println("=================================================");
    }
}
