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
package dev.engnotes.labs.failprop.charting;

import dev.engnotes.labs.failprop.budget.BudgetRunResult;
import dev.engnotes.labs.failprop.budget.BudgetSweepPoint;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler.LegendPosition;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Generates XChart PNGs from a timeout-budget run. PNGs are visual references for the blog post;
 * the CSV is the golden-file contract.
 */
public final class BudgetChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private BudgetChartGenerator() {}

    /**
     * Success rate vs client deadline, one line per policy. The budget line climbs as the
     * deadline tightens (a tight deadline prevents the storm) and crosses above the breaker
     * below one retry-width; the breaker line is roughly flat (deadline-agnostic load relief);
     * no-protection stays on the floor.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveDeadlineSweepChart(BudgetRunResult result, Path outputPath) throws IOException {
        saveSweepChart(result,
                "Success Rate vs Client Deadline - The Deadline Is the Dial",
                "Success Rate (%)",
                BudgetSweepPoint::successPct,
                LegendPosition.InsideNE,
                outputPath);
    }

    /**
     * p99 resolution vs client deadline, one line per policy. The budget line tracks the
     * deadline (a propagated deadline caps every request); the others are flat - the breaker
     * bounds load, not any single request's latency.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveLatencyCapChart(BudgetRunResult result, Path outputPath) throws IOException {
        saveSweepChart(result,
                "p99 Resolution vs Client Deadline - The Budget Caps Latency",
                "p99 Resolution (ms)",
                BudgetSweepPoint::p99ResolutionMs,
                LegendPosition.InsideNW,
                outputPath);
    }

    private static void saveSweepChart(
            BudgetRunResult result,
            String title,
            String yAxisTitle,
            ToDoubleFunction<BudgetSweepPoint> metric,
            LegendPosition legend,
            Path outputPath) throws IOException {

        if (result.sweep().isEmpty()) {
            return;
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title(title)
                .xAxisTitle("Client Deadline (ms)")
                .yAxisTitle(yAxisTitle)
                .build();

        chart.getStyler().setLegendPosition(legend);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        for (String policy : List.of("no-protection", "breaker", "budget")) {
            List<BudgetSweepPoint> points = result.sweep().stream()
                    .filter(p -> p.policy().equals(policy)).toList();
            chart.addSeries(policy,
                    column(points, p -> (double) p.deadlineMs()),
                    column(points, metric)).setMarker(SeriesMarkers.CIRCLE);
        }

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static double[] column(List<BudgetSweepPoint> points, ToDoubleFunction<BudgetSweepPoint> extractor) {
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            values[i] = extractor.applyAsDouble(points.get(i));
        }
        return values;
    }
}
