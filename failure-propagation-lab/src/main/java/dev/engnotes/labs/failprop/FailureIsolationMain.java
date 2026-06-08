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
import dev.engnotes.labs.failprop.bulkhead.BulkheadPolicyPoint;
import dev.engnotes.labs.failprop.bulkhead.BulkheadRunResult;
import dev.engnotes.labs.failprop.bulkhead.BulkheadScenario;
import dev.engnotes.labs.failprop.bulkhead.BulkheadSweepPoint;
import dev.engnotes.labs.failprop.charting.BulkheadChartGenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the failure-isolation experiment (Series 3, Post 5: "Failure Isolation
 * Boundaries" - the capstone).
 *
 * <p>A slow-but-healthy neighbour (route-a) hogs a shared frontend pool and starves a fast
 * interactive route (route-b) that never touches it. The circuit breaker and the timeout budget
 * are blind to this - nothing fails, nothing is slow on the victim's own path - so only a
 * bulkhead, reserving a dedicated slice of the pool for route-b, contains the blast. That ties
 * the series back to Post 1: the cascade is resource coupling, and the fix isolates the
 * resource. Produces:
 * <ul>
 *   <li>a policy-comparison CSV and a bulkhead-sizing CSV (the golden contract);</li>
 *   <li>two PNG charts: success by policy and the sizing trade-off;</li>
 *   <li>{@code manifest.json} and a self-contained {@code report.html}.</li>
 * </ul>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :failure-propagation-lab:runFailureIsolation \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/failure-isolation"
 * </pre>
 */
public final class FailureIsolationMain {

    static final String POLICY_CSV_HEADER =
            "policy,route_a_success_pct,route_a_p99_ms,route_b_success_pct,route_b_p99_ms";
    static final String SIZING_CSV_HEADER =
            "route_b_reserved,route_a_workers,route_a_success_pct,route_b_success_pct";

    private FailureIsolationMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        BulkheadScenario scenario = new BulkheadScenario();
        BulkheadRunResult result = scenario.run(cliArgs);

        Path policyCsv = cliArgs.outputDir().resolve("fp-post5-policy-table.csv");
        writePolicyCsv(result.policies(), policyCsv);
        Path sizingCsv = cliArgs.outputDir().resolve("fp-post5-bulkhead-sizing.csv");
        writeSizingCsv(result.sizing(), sizingCsv);
        System.out.printf("CSV written: %s%n", policyCsv);
        System.out.printf("CSV written: %s%n%n", sizingCsv);

        printPolicyTable(result.policies());

        System.out.println("Generating charts...");
        BulkheadChartGenerator.savePolicyChart(result, cliArgs.outputDir().resolve("fp-post5-policy"));
        BulkheadChartGenerator.saveSizingChart(result, cliArgs.outputDir().resolve("fp-post5-bulkhead-sizing"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.FAILURE_ISOLATION,
                cliArgs,
                args,
                PostArtifacts.csv("Policy comparison", policyCsv),
                PostArtifacts.csv("Bulkhead sizing", sizingCsv));

        printSummary();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Failure Isolation Boundaries (Bulkheads)");
        System.out.println("=================================================");
        System.out.printf("  Window per run: %s%n", args.duration());
        System.out.printf("  Mode:           %s%n", args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:     %s%n", args.outputDir());
        System.out.println();
    }

    private static void writePolicyCsv(List<BulkheadPolicyPoint> policies, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(POLICY_CSV_HEADER);
            w.newLine();
            for (BulkheadPolicyPoint p : policies) {
                w.write(String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f,%.1f",
                        p.policy(), p.routeASuccessPct(), p.routeAp99Ms(),
                        p.routeBSuccessPct(), p.routeBp99Ms()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writeSizingCsv(List<BulkheadSweepPoint> sizing, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SIZING_CSV_HEADER);
            w.newLine();
            for (BulkheadSweepPoint p : sizing) {
                w.write(String.format(Locale.ROOT, "%d,%d,%.1f,%.1f",
                        p.routeBReserved(), p.routeAWorkers(),
                        p.routeASuccessPct(), p.routeBSuccessPct()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printPolicyTable(List<BulkheadPolicyPoint> policies) {
        System.out.printf("  %-11s  %-15s  %-9s  %-15s  %-9s%n",
                "policy", "route-a ok%", "a p99", "route-b ok%", "b p99");
        System.out.println("  " + "-".repeat(68));
        for (BulkheadPolicyPoint p : policies) {
            String marker = p.routeBSuccessPct() < 50.0 ? " <- victim starved" : "";
            System.out.printf(Locale.ROOT, "  %-11s  %-15.1f  %-9.1f  %-15.1f  %-9.1f%s%n",
                    p.policy(), p.routeASuccessPct(), p.routeAp99Ms(),
                    p.routeBSuccessPct(), p.routeBp99Ms(), marker);
        }
        System.out.println();
    }

    private static void printSummary() {
        System.out.println("=================================================");
        System.out.println("  Failure isolation summary");
        System.out.println("=================================================");
        System.out.println("  Lesson: a slow-but-healthy neighbour starves a route that shares its");
        System.out.println("          pool. Nothing fails, so the breaker and the budget are blind -");
        System.out.println("          only a bulkhead, reserving the victim a dedicated slice, helps.");
        System.out.println("          Size it to the protected route's need; over-reserving starves");
        System.out.println("          the neighbour you were trying to keep useful.");
        System.out.println("  Note:   synthetic lab model; production numbers vary, but the resource-");
        System.out.println("          isolation mechanism - the answer to Post 1 - is the same.");
        System.out.println("=================================================");
    }
}
