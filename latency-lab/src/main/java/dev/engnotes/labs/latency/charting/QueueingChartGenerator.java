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
package dev.engnotes.labs.latency.charting;

import dev.engnotes.labs.latency.queueing.SaturationPoint;
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
 * Generates XChart PNG charts from Post 2 saturation sweep data.
 * All methods are static — no instances needed.
 */
public final class QueueingChartGenerator {

    private static final int CHART_WIDTH  = 800;
    private static final int CHART_HEIGHT = 400;

    private QueueingChartGenerator() {}

    /**
     * Saves a throughput vs utilization chart showing the knee of the curve.
     *
     * <p>Plots two series — the intended target RPS and the actual measured throughput —
     * so readers can see where the system saturates and throughput collapses.
     * The path must NOT include the {@code .png} extension; {@link BitmapEncoder}
     * appends it automatically.
     *
     * @param points     ordered list of saturation-sweep data points to plot
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written to disk
     */
    public static void saveThroughputChart(
            List<SaturationPoint> points, Path outputPath) throws IOException {

        if (points.isEmpty()) {
            return;
        }

        int count = points.size();
        double[] utilization = new double[count];
        double[] targetRps = new double[count];
        double[] actualRps = new double[count];

        for (int i = 0; i < count; i++) {
            SaturationPoint point = points.get(i);
            utilization[i] = point.targetUtilization();
            targetRps[i] = point.targetRps();
            actualRps[i] = point.actualThroughputRps();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Throughput vs Utilization — Knee of the Curve")
                .xAxisTitle("Utilization (ρ)")
                .yAxisTitle("Throughput (rps)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Target RPS", utilization, targetRps).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Actual RPS", utilization, actualRps).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Saves a sojourn latency vs utilization chart showing exponential blowup above the knee.
     *
     * <p>Plots p50, p99, and p99.9 percentiles so readers can observe how tail latency
     * diverges rapidly as utilization approaches 1.0.
     * The path must NOT include the {@code .png} extension; {@link BitmapEncoder}
     * appends it automatically.
     *
     * @param points     ordered list of saturation-sweep data points to plot
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written to disk
     */
    public static void saveLatencyChart(
            List<SaturationPoint> points, Path outputPath) throws IOException {

        if (points.isEmpty()) {
            return;
        }

        int count = points.size();
        double[] utilization = new double[count];
        double[] p50Data = new double[count];
        double[] p99Data = new double[count];
        double[] p999Data = new double[count];

        for (int i = 0; i < count; i++) {
            SaturationPoint point = points.get(i);
            utilization[i] = point.targetUtilization();
            p50Data[i] = point.p50Ms();
            p99Data[i] = point.p99Ms();
            p999Data[i] = point.p999Ms();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Latency vs Utilization — Exponential Blowup")
                .xAxisTitle("Utilization (ρ)")
                .yAxisTitle("Sojourn Time (ms)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("p50", utilization, p50Data).setMarker(SeriesMarkers.NONE);
        chart.addSeries("p99", utilization, p99Data).setMarker(SeriesMarkers.NONE);
        chart.addSeries("p99.9", utilization, p999Data).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Saves a Little's Law verification chart comparing measured queue depth against λW.
     *
     * <p>Plots the empirically measured average number of items in the system (L) alongside
     * the value computed from Little's Law (L = λW), so readers can verify the law holds
     * and spot deviations under overload.
     * The path must NOT include the {@code .png} extension; {@link BitmapEncoder}
     * appends it automatically.
     *
     * @param points     ordered list of saturation-sweep data points to plot
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written to disk
     */
    public static void saveLittlesLawChart(
            List<SaturationPoint> points, Path outputPath) throws IOException {

        if (points.isEmpty()) {
            return;
        }

        int count = points.size();
        double[] utilization = new double[count];
        double[] measuredL = new double[count];
        double[] computedL = new double[count];

        for (int i = 0; i < count; i++) {
            SaturationPoint point = points.get(i);
            utilization[i] = point.targetUtilization();
            measuredL[i] = point.avgQueueDepth();
            computedL[i] = point.littlesLaw().computedL();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Little's Law Verification: Measured L vs λW")
                .xAxisTitle("Utilization (ρ)")
                .yAxisTitle("L (avg items in system)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Measured L (queue depth)", utilization, measuredL).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Computed L (λW)", utilization, computedL).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
