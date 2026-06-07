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
package dev.engnotes.labs.backpressure.charting;

import dev.engnotes.labs.backpressure.collapse.CollapseRunResult;
import dev.engnotes.labs.backpressure.collapse.LoadLevelResult;
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

/**
 * Generates XChart PNGs from a collapse sweep. PNGs are visual references for the blog post;
 * the CSV is the golden-file contract.
 */
public final class CollapseChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private CollapseChartGenerator() {}

    /**
     * Goodput vs offered load: the collapse cliff. Plots the ideal plateau a backpressured
     * system would hold against the measured goodput of an unmanaged server, which falls below
     * capacity once overloaded. Retries do not change this curve - goodput is decided by the
     * early requests that beat the deadline, before the backlog (and any retries) build up - so
     * a single goodput series is plotted; the retry damage is in {@link #saveAmplificationChart}.
     *
     * @param result     the full sweep result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveGoodputChart(CollapseRunResult result, Path outputPath) throws IOException {
        List<LoadLevelResult> noRetry = byMode(result, "no-retry");
        if (noRetry.isEmpty()) {
            return;
        }

        double[] offered = offeredAxis(noRetry);
        double[] ideal = column(noRetry, LoadLevelResult::idealGoodputRps);
        double[] goodput = column(noRetry, LoadLevelResult::goodputRps);

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Goodput vs Offered Load - The Collapse Cliff")
                .xAxisTitle("Offered Load (rps)")
                .yAxisTitle("Goodput (rps)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Ideal (with backpressure)", offered, ideal).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Goodput (no backpressure)", offered, goodput).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Effective load vs offered load: the retry storm. The diagonal reference is offered load
     * itself; the retry curve climbs far above it once the server saturates, showing how a
     * retry policy multiplies the load on an already-failing service.
     *
     * @param result     the full sweep result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveAmplificationChart(CollapseRunResult result, Path outputPath) throws IOException {
        List<LoadLevelResult> noRetry = byMode(result, "no-retry");
        List<LoadLevelResult> retry = byMode(result, "retry");
        if (noRetry.isEmpty()) {
            return;
        }

        double[] offered = offeredAxis(noRetry);
        double[] effectiveNoRetry = column(noRetry, LoadLevelResult::effectiveRps);
        double[] effectiveRetry = column(retry, LoadLevelResult::effectiveRps);

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Effective Load vs Offered Load - The Retry Storm")
                .xAxisTitle("Offered Load (rps)")
                .yAxisTitle("Effective Load on Server (rps)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Offered (no retries)", offered, effectiveNoRetry).setMarker(SeriesMarkers.NONE);
        if (!retry.isEmpty()) {
            chart.addSeries("Effective (with retries)", offered, effectiveRetry).setMarker(SeriesMarkers.NONE);
        }

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static List<LoadLevelResult> byMode(CollapseRunResult result, String mode) {
        return result.levels().stream().filter(l -> l.mode().equals(mode)).toList();
    }

    private static double[] offeredAxis(List<LoadLevelResult> levels) {
        double[] values = new double[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            values[i] = levels.get(i).offeredRps();
        }
        return values;
    }

    private static double[] column(
            List<LoadLevelResult> levels,
            java.util.function.ToDoubleFunction<LoadLevelResult> extractor) {
        double[] values = new double[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            values[i] = extractor.applyAsDouble(levels.get(i));
        }
        return values;
    }
}
