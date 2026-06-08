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
import dev.engnotes.labs.failprop.budget.BudgetPolicyPoint;
import dev.engnotes.labs.failprop.budget.BudgetRunResult;
import dev.engnotes.labs.failprop.budget.BudgetScenario;
import dev.engnotes.labs.failprop.budget.BudgetSweepPoint;
import dev.engnotes.labs.failprop.charting.BudgetChartGenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the timeout-budget experiment (Series 3, Post 4: "Timeout Budgeting").
 *
 * <p>Against a partially degraded dependency, sweeps the client deadline across one retry-width
 * and shows what a propagated deadline buys that an uncoordinated per-call timeout - and a
 * circuit breaker - cannot: p99 that tracks the deadline, no work started past it, and, at a
 * deadline tight enough to admit a single attempt, prevention of the retry storm itself.
 * Produces:
 * <ul>
 *   <li>a deadline-sweep CSV and a tight-deadline policy-comparison CSV (the golden contract);</li>
 *   <li>two PNG charts: success vs deadline (the dial) and p99 vs deadline (the latency cap);</li>
 *   <li>{@code manifest.json} and a self-contained {@code report.html}.</li>
 * </ul>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :failure-propagation-lab:runTimeoutBudgets \
 *       -Pargs="--deterministic --duration 5s --output-dir ./results/timeout-budgets"
 * </pre>
 */
public final class TimeoutBudgetsMain {

    static final String SWEEP_CSV_HEADER =
            "deadline_ms,policy,success_pct,db_attempts_per_request,p50_resolution_ms,p99_resolution_ms";
    static final String TABLE_CSV_HEADER =
            "policy,success_pct,db_attempts_per_request,past_deadline_pct,p50_resolution_ms,p99_resolution_ms";

    private TimeoutBudgetsMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if output files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        BudgetScenario scenario = new BudgetScenario();
        BudgetRunResult result = scenario.run(cliArgs);

        Path sweepCsv = cliArgs.outputDir().resolve("fp-post4-deadline-sweep.csv");
        writeSweepCsv(result.sweep(), sweepCsv);
        Path tableCsv = cliArgs.outputDir().resolve("fp-post4-policy-table.csv");
        writeTableCsv(result.table(), tableCsv);
        System.out.printf("CSV written: %s%n", sweepCsv);
        System.out.printf("CSV written: %s%n%n", tableCsv);

        printTable(result.table());

        System.out.println("Generating charts...");
        BudgetChartGenerator.saveDeadlineSweepChart(result, cliArgs.outputDir().resolve("fp-post4-deadline-sweep"));
        BudgetChartGenerator.saveLatencyCapChart(result, cliArgs.outputDir().resolve("fp-post4-latency-cap"));
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.TIMEOUT_BUDGETS,
                cliArgs,
                args,
                PostArtifacts.csv("Deadline sweep", sweepCsv),
                PostArtifacts.csv("Policy table", tableCsv));

        printSummary();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Timeout Budgeting");
        System.out.println("=================================================");
        System.out.printf("  Window per run: %s%n", args.duration());
        System.out.printf("  Mode:           %s%n", args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:     %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSweepCsv(List<BudgetSweepPoint> sweep, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(SWEEP_CSV_HEADER);
            w.newLine();
            for (BudgetSweepPoint p : sweep) {
                w.write(String.format(Locale.ROOT, "%d,%s,%.1f,%.2f,%.1f,%.1f",
                        p.deadlineMs(), p.policy(), p.successPct(),
                        p.dbAttemptsPerRequest(), p.p50ResolutionMs(), p.p99ResolutionMs()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void writeTableCsv(List<BudgetPolicyPoint> table, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(TABLE_CSV_HEADER);
            w.newLine();
            for (BudgetPolicyPoint p : table) {
                w.write(String.format(Locale.ROOT, "%s,%.1f,%.2f,%.1f,%.1f,%.1f",
                        p.policy(), p.successPct(), p.dbAttemptsPerRequest(),
                        p.pastDeadlinePct(), p.p50ResolutionMs(), p.p99ResolutionMs()));
                w.newLine();
            }
            w.flush();
        }
    }

    private static void printTable(List<BudgetPolicyPoint> table) {
        System.out.printf("  tight deadline (%dms):%n", BudgetScenario.TIGHT_DEADLINE_MS);
        System.out.printf("  %-15s  %-10s  %-13s  %-13s  %-9s  %-9s%n",
                "policy", "success%", "db_att/req", "past_dl%", "p50(ms)", "p99(ms)");
        System.out.println("  " + "-".repeat(80));
        for (BudgetPolicyPoint p : table) {
            System.out.printf(Locale.ROOT, "  %-15s  %-10.1f  %-13.2f  %-13.1f  %-9.1f  %-9.1f%n",
                    p.policy(), p.successPct(), p.dbAttemptsPerRequest(),
                    p.pastDeadlinePct(), p.p50ResolutionMs(), p.p99ResolutionMs());
        }
        System.out.println();
    }

    private static void printSummary() {
        System.out.println("=================================================");
        System.out.println("  Budget summary");
        System.out.println("=================================================");
        System.out.println("  Lesson: a propagated deadline is the latency dial - p99 tracks it,");
        System.out.println("          nothing runs past it, and a deadline tight enough to admit one");
        System.out.println("          attempt prevents the retry storm before it forms. The breaker");
        System.out.println("          bounds load, not latency; production wants both.");
        System.out.println("  Note:   synthetic lab model; production numbers vary, but the");
        System.out.println("          deadline-propagation mechanism is the same.");
        System.out.println("=================================================");
    }
}
