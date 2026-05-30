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
import dev.engnotes.labs.commons.csv.CsvSnapshotWriter;
import dev.engnotes.labs.commons.histogram.LatencyHistogram;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import dev.engnotes.labs.commons.terminal.TerminalRenderer;
import dev.engnotes.labs.latency.charting.LatencyChartGenerator;
import dev.engnotes.labs.latency.simulation.LatencyInjector;
import dev.engnotes.labs.latency.simulation.RequestSimulator;
import dev.engnotes.labs.latency.simulation.TailAmplificationScenario;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for the tail-latency amplification experiment.
 *
 * <p>Runs two back-to-back scenarios and writes CSV snapshots + PNG charts to
 * {@code --output-dir}:
 *
 * <ol>
 *   <li><b>Baseline</b> — single service with a bimodal latency distribution (99% fast, 1% tail).
 *       Shows that p50 looks healthy while p99 is already elevated.</li>
 *   <li><b>Tail Amplification</b> — same service calls 5 downstream dependencies in parallel.
 *       The top-level latency is the max of all 5, making the p99 jump dramatically.</li>
 * </ol>
 *
 * <p>Invoke via Gradle:
 * <pre>
 *   ./gradlew :latency-lab:runTailLatency -Pargs="--deterministic --duration 30s --concurrency 100"
 * </pre>
 */
public final class TailLatencyMain {

    private static final String SCENARIO_BASELINE = "Scenario 1: Normal Latency Distribution";
    private static final String SCENARIO_TAIL     = "Scenario 2: Tail Amplification (5-service fan-out)";

    private TailLatencyMain() {}

    /**
     * Main entry point.
     *
     * @param args CLI arguments (see {@link CliParser} for supported flags)
     * @throws IOException if CSV or chart files cannot be written
     */
    public static void main(String[] args) throws IOException {
        CliArgs cliArgs = CliParser.parse(args);

        printBanner(cliArgs);

        // --- Scenario 1: Baseline ---
        System.out.println("Running " + SCENARIO_BASELINE + "...");
        LatencyInjector baselineInjector = new LatencyInjector(cliArgs.isDeterministic());
        LatencyHistogram baselineHistogram = new LatencyHistogram();
        RequestSimulator baselineSimulator =
                new RequestSimulator(baselineInjector, baselineHistogram, cliArgs.isDeterministic());

        List<PercentileSnapshot> baselineSnapshots = baselineSimulator.run(cliArgs);

        Path baselineCsv = cliArgs.outputDir().resolve("post1-baseline.csv");
        writeSnapshots(baselineSnapshots, baselineCsv);
        printSnapshotTable(baselineSnapshots, cliArgs);
        System.out.printf("  CSV written: %s%n%n", baselineCsv);

        // --- Scenario 2: Tail Amplification ---
        System.out.println("Running " + SCENARIO_TAIL + "...");
        LatencyInjector tailInjector = new LatencyInjector(cliArgs.isDeterministic());
        LatencyHistogram tailHistogram = new LatencyHistogram();
        TailAmplificationScenario tailScenario =
                new TailAmplificationScenario(tailInjector, tailHistogram, cliArgs.isDeterministic());

        List<PercentileSnapshot> tailSnapshots = tailScenario.run(cliArgs);

        Path tailCsv = cliArgs.outputDir().resolve("post1-tail-amplification.csv");
        writeSnapshots(tailSnapshots, tailCsv);
        printSnapshotTable(tailSnapshots, cliArgs);
        System.out.printf("  CSV written: %s%n%n", tailCsv);

        // --- Generate charts ---
        System.out.println("Generating charts...");
        saveCharts(baselineSnapshots, tailSnapshots, cliArgs.outputDir());
        System.out.printf("  Charts saved to: %s%n%n", cliArgs.outputDir());

        PostArtifacts.write(
                ExperimentRegistry.TAIL_LATENCY,
                cliArgs,
                args,
                PostArtifacts.csv("Baseline", baselineCsv),
                PostArtifacts.csv("Tail amplification", tailCsv));

        // --- Final comparison ---
        printComparison(baselineSnapshots, tailSnapshots);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void printBanner(CliArgs args) {
        System.out.println("=================================================");
        System.out.println("  Tail Latency and Fan-out Amplification");
        System.out.println("=================================================");
        System.out.printf("  Duration:    %s%n", args.duration());
        System.out.printf("  Concurrency: %d virtual clients%n", args.concurrency());
        System.out.printf("  Mode:        %s%n", args.isDeterministic() ? "deterministic" : "live");
        System.out.printf("  Output dir:  %s%n", args.outputDir());
        System.out.println();
    }

    private static void writeSnapshots(List<PercentileSnapshot> snapshots, Path csvPath)
            throws IOException {
        try (CsvSnapshotWriter writer = CsvSnapshotWriter.open(csvPath)) {
            for (PercentileSnapshot snap : snapshots) {
                writer.write(snap);
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void printSnapshotTable(List<PercentileSnapshot> snapshots, CliArgs args) {
        if (snapshots.isEmpty()) {
            System.out.println("  (no snapshots — duration too short for one interval)");
            return;
        }
        // Use streaming renderer to display each snapshot line
        TerminalRenderer renderer = TerminalRenderer.streaming(System.out);
        long durationSecs = args.duration().toSeconds();
        for (PercentileSnapshot snap : snapshots) {
            long remaining = Math.max(0L, durationSecs - snap.elapsedSeconds());
            renderer.render(snap, durationSecs, remaining);
        }
        renderer.finish("");
    }

    private static void saveCharts(
            List<PercentileSnapshot> baseline,
            List<PercentileSnapshot> tail,
            Path outputDir) throws IOException {

        LatencyChartGenerator.saveLatencyChart(
                baseline,
                outputDir.resolve("post1-baseline"),
                "Normal Latency — p50 vs p99 vs p99.9");

        LatencyChartGenerator.saveLatencyChart(
                tail,
                outputDir.resolve("post1-tail-amplification"),
                "Tail Amplification — p50 vs p99 vs p99.9");

        LatencyChartGenerator.saveComparisonChart(
                baseline,
                tail,
                outputDir.resolve("post1-comparison"));
    }

    private static void printComparison(
            List<PercentileSnapshot> baseline,
            List<PercentileSnapshot> tail) {

        System.out.println("=================================================");
        System.out.println("  Final Comparison");
        System.out.println("=================================================");

        if (baseline.isEmpty() || tail.isEmpty()) {
            System.out.println("  Not enough data to compare.");
            return;
        }

        PercentileSnapshot b = baseline.get(baseline.size() - 1);
        PercentileSnapshot t = tail.get(tail.size() - 1);

        System.out.printf("  %-26s  p50=%6.1fms  p99=%6.1fms  p99.9=%7.1fms%n",
                "Baseline:", b.p50Ms(), b.p99Ms(), b.p999Ms());
        System.out.printf("  %-26s  p50=%6.1fms  p99=%6.1fms  p99.9=%7.1fms%n",
                "Tail-Amplified:", t.p50Ms(), t.p99Ms(), t.p999Ms());
        System.out.println();

        double p99Multiplier = b.p99Ms() > 0 ? t.p99Ms() / b.p99Ms() : 0.0;
        System.out.printf("  p99 amplification factor: %.1fx%n", p99Multiplier);
        System.out.println("=================================================");
    }
}
