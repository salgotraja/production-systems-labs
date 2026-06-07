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
import dev.engnotes.labs.failprop.breaker.BreakerRunResult;
import dev.engnotes.labs.failprop.breaker.BreakerStormScenario;
import dev.engnotes.labs.failprop.breaker.BreakerSweepPoint;
import dev.engnotes.labs.failprop.breaker.BreakerWindowSample;
import dev.engnotes.labs.failprop.charting.BreakerChartGenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the circuit-breaker experiment (Series 3, Post 3: "Circuit Breaker Design").
 *
 * <p>Puts a hand-rolled circuit breaker on every client edge of the Series 3 topologies and
 * shows what it buys: against a hard-down dependency, Post 2's R^2 storm collapses to a probe
 * trickle and hangs become fast fails; against a transient degradation on Post 1's shared-pool
 * topology, fail-fast releases the shared workers and the route that never touches the failure
 * survives. Produces:
 * <ul>
 *   <li>a hard-down comparison CSV and a blast-radius timeline CSV (the golden contract);</li>
 *   <li>two PNG charts: route-b's survival and the storm suppression;</li>
 *   <li>{@code manifest.json} and a self-contained {@code report.html}.</li>
 * </ul>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :failure-propagation-lab:runCircuitBreaker \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/circuit-breaker"
 * </pre>
 */
public final class CircuitBreakerMain {

    static final String SWEEP_CSV_HEADER =
            "policy,success_pct,db_attempts_per_request,db_attempts_rps,p50_resolution_ms,p99_resolution_ms";
    static final String TIMELINE_CSV_HEADER =
            "window_start_ms,naive_route_a_pct,naive_route_b_pct,breaker_route_a_pct,breaker_route_b_pct,"
            + "naive_db_attempts_rps,breaker_db_attempts_rps,frontend_edge_state,database_edge_state";

    private CircuitBreakerMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        BreakerStormScenario scenario = new BreakerStormScenario();
        BreakerRunResult result = scenario.run(cliArgs);

        Path sweepCsv = cliArgs.outputDir().resolve("fp-post3-breaker-sweep.csv");
        writeSweepCsv(result.sweep(), sweepCsv);
        Path timelineCsv = cliArgs.outputDir().resolve("fp-post3-blast-radius.csv");
        writeTimelineCsv(result.timeline(), timelineCsv);
        System.out.printf("CSV written: %s%n", sweepCsv);
        System.out.printf("CSV written: %s%n%n", timelineCsv);

        printSweepTable(result.sweep());

        System.out.println("Generating charts...");
        BreakerChartGenerator.saveBlastRadiusChart(result, cliArgs.outputDir().resolve("fp-post3-blast-radius"));
        BreakerChartGenerator.saveStormSuppressionChart(result,
                cliArgs.outputDir().resolve("fp-post3-storm-suppression"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.CIRCUIT_BREAKER,
                cliArgs,
                args,
                PostArtifacts.csv("Hard-down comparison", sweepCsv),
                PostArtifacts.csv("Blast-radius timeline", timelineCsv));

        printSummary(result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Circuit Breaker Design");
        System.out.println("=================================================");
        System.out.printf("  Window per run: %s%n", args.duration());
        System.out.printf("  Mode:           %s%n", args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:     %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSweepCsv(List<BreakerSweepPoint> sweep, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SWEEP_CSV_HEADER);
            w.newLine();
            for (BreakerSweepPoint point : sweep) {
                w.write(String.format(Locale.ROOT, "%s,%.1f,%.2f,%.1f,%.1f,%.1f",
                        point.policy(),
                        point.successPct(),
                        point.dbAttemptsPerRequest(),
                        point.dbAttemptsRps(),
                        point.p50ResolutionMs(),
                        point.p99ResolutionMs()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writeTimelineCsv(List<BreakerWindowSample> timeline, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(TIMELINE_CSV_HEADER);
            w.newLine();
            for (BreakerWindowSample s : timeline) {
                w.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d",
                        s.windowStartMs(),
                        s.naiveRouteAPct(),
                        s.naiveRouteBPct(),
                        s.breakerRouteAPct(),
                        s.breakerRouteBPct(),
                        s.naiveDbAttemptsRps(),
                        s.breakerDbAttemptsRps(),
                        s.frontendEdgeState(),
                        s.databaseEdgeState()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printSweepTable(List<BreakerSweepPoint> sweep) {
        System.out.printf("  %-13s  %-10s  %-13s  %-12s  %-9s  %-9s%n",
                "policy", "success%", "db_att/req", "db_att_rps", "p50(ms)", "p99(ms)");
        System.out.println("  " + "-".repeat(76));
        for (BreakerSweepPoint p : sweep) {
            System.out.printf(Locale.ROOT, "  %-13s  %-10.1f  %-13.2f  %-12.1f  %-9.1f  %-9.1f%n",
                    p.policy(), p.successPct(), p.dbAttemptsPerRequest(),
                    p.dbAttemptsRps(), p.p50ResolutionMs(), p.p99ResolutionMs());
        }
        System.out.println();
    }

    private static void printSummary(BreakerRunResult result) {
        System.out.println("=================================================");
        System.out.println("  Breaker summary");
        System.out.println("=================================================");
        System.out.println("  Lesson: the breaker cannot buy successes against a real outage -");
        System.out.println("          it buys cheap failures, an unharassed dependency, and the");
        System.out.println("          survival of routes that share resources with the failing one.");
        System.out.println("  Note:   synthetic lab model; production numbers vary, but the");
        System.out.println("          state machine and the trade-offs are the same.");
        System.out.println("=================================================");
    }
}
