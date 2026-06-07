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

import dev.engnotes.labs.backpressure.admission.AdmissionPointResult;
import dev.engnotes.labs.backpressure.admission.AdmissionRunResult;
import dev.engnotes.labs.backpressure.admission.AdmissionScenario;
import dev.engnotes.labs.backpressure.admission.AdmissionSimulator;
import dev.engnotes.labs.backpressure.charting.AdmissionChartGenerator;
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
 * Entry point for the admission-control experiment (Series 2, Post 2: "Admission Control
 * Design").
 *
 * <p>Demonstrates the concurrency limit as the design knob that turns Post 1's collapse back into
 * a plateau. Produces two CSVs (limit sweep and offered-load plateau), two PNG charts, plus
 * {@code manifest.json} and {@code report.html}.
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :backpressure-playground:runAdmissionControl \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/admission-control"
 * </pre>
 */
public final class AdmissionControlMain {

    static final String LIMIT_SWEEP_CSV_HEADER =
            "admission_limit,offered_rps,goodput_rps,reject_pct,served_late_pct,p99_ms,utilization_pct";
    static final String PLATEAU_CSV_HEADER = "mode,offered_rps,goodput_rps,reject_pct,p99_ms";

    private AdmissionControlMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        AdmissionRunResult result = new AdmissionScenario().run(cliArgs);
        printBanner(cliArgs, result);

        Path limitCsv = cliArgs.outputDir().resolve("bp-post2-limit-sweep.csv");
        writeLimitSweepCsv(result.limitSweep(), limitCsv);
        Path plateauCsv = cliArgs.outputDir().resolve("bp-post2-plateau.csv");
        writePlateauCsv(result, plateauCsv);
        System.out.printf("CSV written: %s%n", limitCsv);
        System.out.printf("CSV written: %s%n%n", plateauCsv);

        printLimitSweepTable(result);

        System.out.println("Generating charts...");
        AdmissionChartGenerator.saveLimitSweepChart(result, cliArgs.outputDir().resolve("bp-post2-limit-sweep"));
        AdmissionChartGenerator.savePlateauChart(result, cliArgs.outputDir().resolve("bp-post2-plateau"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.ADMISSION_CONTROL,
                cliArgs,
                args,
                PostArtifacts.csv("Limit sweep", limitCsv),
                PostArtifacts.csv("Offered-load plateau", plateauCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args, AdmissionRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Admission Control Design");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:           %.0f rps%n", result.serverCapacityRps());
        System.out.printf("  Little's-Law limit: %d in-flight (capacity x deadline)%n", result.littlesLawLimit());
        System.out.printf("  Window:             %s%n", args.duration());
        System.out.printf("  Output dir:         %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeLimitSweepCsv(List<AdmissionPointResult> points, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(LIMIT_SWEEP_CSV_HEADER);
            w.newLine();
            for (AdmissionPointResult p : points) {
                w.write(String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f",
                        limitLabel(p.admissionLimit()),
                        p.offeredRps(),
                        p.goodputRps(),
                        p.rejectPct(),
                        p.servedLatePct(),
                        p.p99Ms(),
                        p.utilizationPct()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writePlateauCsv(AdmissionRunResult result, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(PLATEAU_CSV_HEADER);
            w.newLine();
            writePlateauRows(w, "no-control", result.offeredNoControl());
            writePlateauRows(w, "admission-limited", result.offeredLimited());
            w.flush();
        }
    }

    private static void writePlateauRows(BufferedWriter w, String mode, List<AdmissionPointResult> points)
            throws IOException {
        for (AdmissionPointResult p : points) {
            w.write(String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f,%.1f",
                    mode, p.offeredRps(), p.goodputRps(), p.rejectPct(), p.p99Ms()));
            w.newLine();
        }
    }

    private static String limitLabel(int limit) {
        return limit == AdmissionSimulator.NO_LIMIT ? "none" : Integer.toString(limit);
    }

    private static void printLimitSweepTable(AdmissionRunResult result) {
        System.out.printf("  %-7s  %-9s  %-9s  %-9s  %-10s  %-9s  %-7s%n",
                "limit", "offered", "goodput", "reject%", "servLate%", "p99(ms)", "util%");
        System.out.println("  " + "-".repeat(74));
        AdmissionPointResult best = bestGoodput(result.limitSweep());
        for (AdmissionPointResult p : result.limitSweep()) {
            String marker = p == best ? " <- sweet spot" : "";
            System.out.printf(Locale.ROOT,
                    "  %-7s  %-9.1f  %-9.1f  %-9.1f  %-10.1f  %-9.1f  %-7.1f%s%n",
                    limitLabel(p.admissionLimit()), p.offeredRps(), p.goodputRps(),
                    p.rejectPct(), p.servedLatePct(), p.p99Ms(), p.utilizationPct(), marker);
        }
        System.out.println();
    }

    private static void printSummary(AdmissionRunResult result) {
        AdmissionPointResult best = bestGoodput(result.limitSweep());
        System.out.println("=================================================");
        System.out.println("  Admission summary");
        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "  Capacity:           %.0f rps%n", result.serverCapacityRps());
        System.out.printf("  Little's-Law limit: %d in-flight%n", result.littlesLawLimit());
        if (best != null) {
            System.out.printf(Locale.ROOT, "  Best goodput:       %.1f rps at limit %s%n",
                    best.goodputRps(), limitLabel(best.admissionLimit()));
        }
        System.out.println("  Lesson: size the admission limit to capacity x deadline. Too low rejects");
        System.out.println("          burst traffic the valley could absorb; too high lets the backlog");
        System.out.println("          grow until waits exceed the deadline and goodput collapses.");
        System.out.println("  Note:   synthetic lab model; the sweet-spot limit and numbers vary in");
        System.out.println("          production, but the design rule (size to capacity x deadline) holds.");
        System.out.println("=================================================");
    }

    private static AdmissionPointResult bestGoodput(List<AdmissionPointResult> points) {
        return points.stream()
                .max((a, b) -> Double.compare(a.goodputRps(), b.goodputRps()))
                .orElse(null);
    }
}
