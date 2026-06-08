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

import dev.engnotes.labs.failprop.cascade.CascadeRunResult;
import dev.engnotes.labs.failprop.cascade.CascadeSweepPoint;
import dev.engnotes.labs.failprop.cascade.CascadeWindowSample;
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
 * Generates XChart PNGs from a cascading-failures run. PNGs are visual references for the blog
 * post; the CSV is the golden-file contract.
 */
public final class CascadeChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private CascadeChartGenerator() {}

    /**
     * Success rate vs database service time, one line per route. The headline is route b
     * collapsing despite never calling the database - the cascade travels through the shared
     * frontend pool, not through any data dependency.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveSweepChart(CascadeRunResult result, Path outputPath) throws IOException {
        List<CascadeSweepPoint> routeA = byRoute(result, "route-a");
        List<CascadeSweepPoint> routeB = byRoute(result, "route-b");
        if (routeA.isEmpty() || routeB.isEmpty()) {
            return;
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Success Rate vs Database Service Time - The Cascade")
                .xAxisTitle("Database Service Time (ms)")
                .yAxisTitle("Success Rate (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideSW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("route-a (touches the database)",
                column(routeA, p -> (double) p.dbServiceMs()),
                column(routeA, CascadeSweepPoint::successPct)).setMarker(SeriesMarkers.CIRCLE);
        chart.addSeries("route-b (never touches it)",
                column(routeB, p -> (double) p.dbServiceMs()),
                column(routeB, CascadeSweepPoint::successPct)).setMarker(SeriesMarkers.DIAMOND);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Per-window route success through the mid-run database degradation. Route a dies first;
     * route b follows once the shared frontend pool is exhausted by waiting route-a work.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveTimelineChart(CascadeRunResult result, Path outputPath) throws IOException {
        List<CascadeWindowSample> timeline = result.timeline();
        if (timeline.isEmpty()) {
            return;
        }

        double[] windowStartS = new double[timeline.size()];
        double[] routeA = new double[timeline.size()];
        double[] routeB = new double[timeline.size()];
        for (int i = 0; i < timeline.size(); i++) {
            windowStartS[i] = timeline.get(i).windowStartMs() / 1000.0;
            routeA[i] = timeline.get(i).routeASuccessPct();
            routeB[i] = timeline.get(i).routeBSuccessPct();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Route Success Through a Mid-Run Database Degradation")
                .xAxisTitle("Time (s)")
                .yAxisTitle("Success Rate (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideSW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("route-a (touches the database)", windowStartS, routeA).setMarker(SeriesMarkers.NONE);
        chart.addSeries("route-b (never touches it)", windowStartS, routeB).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static List<CascadeSweepPoint> byRoute(CascadeRunResult result, String route) {
        return result.sweep().stream().filter(p -> p.route().equals(route)).toList();
    }

    private static double[] column(List<CascadeSweepPoint> points, ToDoubleFunction<CascadeSweepPoint> extractor) {
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            values[i] = extractor.applyAsDouble(points.get(i));
        }
        return values;
    }
}
