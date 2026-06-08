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

import dev.engnotes.labs.failprop.retrystorm.StormRunResult;
import dev.engnotes.labs.failprop.retrystorm.StormSweepPoint;
import dev.engnotes.labs.failprop.retrystorm.StormWindowSample;
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
 * Generates XChart PNGs from a retry-storms run. PNGs are visual references for the blog post;
 * the CSV is the golden-file contract.
 */
public final class StormChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private StormChartGenerator() {}

    /**
     * Database attempt rate through a transient degradation, no-retry vs retrying. The
     * headline is the storm's inertia: the trigger clears at 2.5s but the retrying line keeps
     * decaying for over a second - in-flight retry chains are load the failure left behind.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveStormTimelineChart(StormRunResult result, Path outputPath) throws IOException {
        List<StormWindowSample> timeline = result.timeline();
        if (timeline.isEmpty()) {
            return;
        }

        double[] windowStartS = new double[timeline.size()];
        double[] r1 = new double[timeline.size()];
        double[] r3 = new double[timeline.size()];
        for (int i = 0; i < timeline.size(); i++) {
            windowStartS[i] = timeline.get(i).windowStartMs() / 1000.0;
            r1[i] = timeline.get(i).r1DbAttemptsRps();
            r3[i] = timeline.get(i).r3DbAttemptsRps();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Database Attempt Rate Through a 1s Degradation - The Retry Storm")
                .xAxisTitle("Time (s)")
                .yAxisTitle("Database Attempts (rps)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("no retries (R=1)", windowStartS, r1).setMarker(SeriesMarkers.NONE);
        chart.addSeries("3 attempts per hop (R=3)", windowStartS, r3).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Database attempts per client request vs attempts-per-hop, healthy vs hard-down. Healthy
     * stays flat at 1.0 (retries never fire); hard-down grows as R^2 - the multiplicative
     * cost of stacking per-hop retry policies.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveAmplificationChart(StormRunResult result, Path outputPath) throws IOException {
        List<StormSweepPoint> healthy = byMode(result, "healthy");
        List<StormSweepPoint> degraded = byMode(result, "degraded");
        if (healthy.isEmpty() || degraded.isEmpty()) {
            return;
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Database Attempts per Client Request - Retry Amplification")
                .xAxisTitle("Attempts per Hop (R)")
                .yAxisTitle("Database Attempts per Request")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("healthy database",
                column(healthy, p -> (double) p.attemptsPerHop()),
                column(healthy, StormSweepPoint::dbAttemptsPerRequest)).setMarker(SeriesMarkers.CIRCLE);
        chart.addSeries("hard-down database",
                column(degraded, p -> (double) p.attemptsPerHop()),
                column(degraded, StormSweepPoint::dbAttemptsPerRequest)).setMarker(SeriesMarkers.DIAMOND);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Client success through the transient degradation, no-retry vs retrying. This is the won
     * bet: retries hold clients near 100% through a blip that costs the no-retry run several
     * windows of total failure - the price is the attempt-rate spike in the storm chart.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveRescueChart(StormRunResult result, Path outputPath) throws IOException {
        List<StormWindowSample> timeline = result.timeline();
        if (timeline.isEmpty()) {
            return;
        }

        double[] windowStartS = new double[timeline.size()];
        double[] r1 = new double[timeline.size()];
        double[] r3 = new double[timeline.size()];
        for (int i = 0; i < timeline.size(); i++) {
            windowStartS[i] = timeline.get(i).windowStartMs() / 1000.0;
            r1[i] = timeline.get(i).r1SuccessPct();
            r3[i] = timeline.get(i).r3SuccessPct();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Client Success Through a 1s Degradation - The Rescue")
                .xAxisTitle("Time (s)")
                .yAxisTitle("Success Rate (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideSE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("no retries (R=1)", windowStartS, r1).setMarker(SeriesMarkers.NONE);
        chart.addSeries("3 attempts per hop (R=3)", windowStartS, r3).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static List<StormSweepPoint> byMode(StormRunResult result, String mode) {
        return result.sweep().stream().filter(p -> p.mode().equals(mode)).toList();
    }

    private static double[] column(List<StormSweepPoint> points, ToDoubleFunction<StormSweepPoint> extractor) {
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            values[i] = extractor.applyAsDouble(points.get(i));
        }
        return values;
    }
}
