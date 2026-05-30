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
import dev.engnotes.labs.latency.charting.SloChartGenerator;
import dev.engnotes.labs.latency.slo.SloRunResult;
import dev.engnotes.labs.latency.slo.SloScenario;
import dev.engnotes.labs.latency.slo.SloSummary;
import dev.engnotes.labs.latency.slo.SloTarget;
import dev.engnotes.labs.latency.slo.SloWindow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class SloPolicyMain {

    static final String SUMMARY_HEADER =
            "pattern,total_requests,good_requests,bad_requests,achieved_sli_pct,"
            + "worst_burn_rate,final_budget_remaining_pct,alert_triggered";
    static final String WINDOW_HEADER =
            "pattern,elapsed_s,total_requests,good_requests,bad_requests,p99_ms,"
            + "bad_event_pct,burn_rate,budget_remaining_pct,alerting";

    private SloPolicyMain() {}

    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);
        SloTarget target = SloTarget.parse(cliArgs.extra("slo-target"));

        printBanner(cliArgs, target);

        SloRunResult result = new SloScenario().run(target, cliArgs.duration().toSeconds(), cliArgs.concurrency());

        Path summaryCsv = cliArgs.outputDir().resolve("post6-slo-summary.csv");
        writeSummaryCsv(result, summaryCsv);
        Path windowsCsv = cliArgs.outputDir().resolve("post6-slo-windows.csv");
        writeWindowsCsv(result, windowsCsv);

        SloChartGenerator.saveBurnRateChart(result.windows(), cliArgs.outputDir().resolve("post6-burn-rate"));
        SloChartGenerator.saveBudgetChart(result.summaries(), cliArgs.outputDir().resolve("post6-error-budget"));

        System.out.printf("CSV written: %s%n", summaryCsv);
        System.out.printf("CSV written: %s%n", windowsCsv);
        System.out.printf("Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.SLO_POLICY,
                cliArgs,
                args,
                PostArtifacts.csv("Summary", summaryCsv),
                PostArtifacts.csv("Windows", windowsCsv));

        printSummary(result);
    }

    private static void printBanner(CliArgs args, SloTarget target) {
        System.out.println("=================================================");
        System.out.println("  SLO Policy and Burn-rate Simulation");
        System.out.println("=================================================");
        System.out.printf("  Duration:       %s%n", args.duration());
        System.out.printf("  Request rate:   %d rps%n", args.concurrency() * 100);
        System.out.printf("  SLO target:     p99<%dms at %.1f%%%n",
                target.latencyThresholdMs(),
                target.objectivePct());
        System.out.printf("  Error budget:   %.1f%%%n", target.errorBudgetPct());
        System.out.printf("  Output dir:     %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSummaryCsv(SloRunResult result, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(SUMMARY_HEADER);
            writer.newLine();
            for (SloSummary summary : result.summaries()) {
                writer.write(String.format(Locale.ROOT, "%s,%d,%d,%d,%.2f,%.2f,%.2f,%s",
                        summary.pattern().label(),
                        summary.totalRequests(),
                        summary.goodRequests(),
                        summary.badRequests(),
                        summary.achievedSliPct(),
                        summary.worstBurnRate(),
                        summary.finalBudgetRemainingPct(),
                        summary.alertTriggered()));
                writer.newLine();
            }
            writer.flush();
        }
    }

    private static void writeWindowsCsv(SloRunResult result, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(WINDOW_HEADER);
            writer.newLine();
            for (SloWindow window : result.windows()) {
                writer.write(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%.1f,%.2f,%.2f,%.2f,%s",
                        window.pattern().label(),
                        window.elapsedSeconds(),
                        window.totalRequests(),
                        window.goodRequests(),
                        window.badRequests(),
                        window.p99Ms(),
                        window.badEventPct(),
                        window.burnRate(),
                        window.budgetRemainingPct(),
                        window.alerting()));
                writer.newLine();
            }
            writer.flush();
        }
    }

    private static void printSummary(SloRunResult result) {
        System.out.println("=================================================");
        System.out.println("  SLO Summary");
        System.out.println("=================================================");
        System.out.printf("  %-20s  %-9s  %-8s  %-8s  %-9s  %-9s  %-8s%n",
                "pattern", "SLI %", "bad", "total", "burn x", "budget %", "alert");
        System.out.println("  " + "-".repeat(86));
        for (SloSummary summary : result.summaries()) {
            System.out.printf("  %-20s  %-9.2f  %-8d  %-8d  %-9.2f  %-9.2f  %-8s%n",
                    summary.pattern().label(),
                    summary.achievedSliPct(),
                    summary.badRequests(),
                    summary.totalRequests(),
                    summary.worstBurnRate(),
                    summary.finalBudgetRemainingPct(),
                    summary.alertTriggered());
        }
        System.out.println("=================================================");
    }
}
