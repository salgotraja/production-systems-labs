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

import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
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
 * Generates XChart PNG charts from latency snapshot data.
 * All methods are static - no instances needed.
 */
public final class LatencyChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private LatencyChartGenerator() {}

    /**
     * Saves a p50 vs p99 vs p99.9 latency-over-time chart to outputPath.
     *
     * <p>The path must NOT include the {@code .png} extension; {@link BitmapEncoder}
     * appends it automatically.
     *
     * @param snapshots  ordered list of percentile snapshots to plot
     * @param outputPath destination path without the {@code .png} extension
     * @param title      chart title displayed at the top
     * @throws IOException if the PNG cannot be written to disk
     */
    public static void saveLatencyChart(
            List<PercentileSnapshot> snapshots,
            Path outputPath,
            String title) throws IOException {

        if (snapshots.isEmpty()) {
            return;
        }

        int count = snapshots.size();
        double[] xData = new double[count];
        double[] p50Data = new double[count];
        double[] p99Data = new double[count];
        double[] p999Data = new double[count];

        for (int i = 0; i < count; i++) {
            PercentileSnapshot snapshot = snapshots.get(i);
            xData[i] = (double) snapshot.elapsedSeconds();
            p50Data[i] = snapshot.p50Ms();
            p99Data[i] = snapshot.p99Ms();
            p999Data[i] = snapshot.p999Ms();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title(title)
                .xAxisTitle("Elapsed (seconds)")
                .yAxisTitle("Latency (ms)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("p50", xData, p50Data).setMarker(SeriesMarkers.NONE);
        chart.addSeries("p99", xData, p99Data).setMarker(SeriesMarkers.NONE);
        chart.addSeries("p99.9", xData, p999Data).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Saves a comparison chart showing p99 for a baseline scenario versus
     * a tail-amplified fan-out scenario.
     *
     * <p>When the two lists differ in length, only the shorter length is plotted.
     * The path must NOT include the {@code .png} extension; {@link BitmapEncoder}
     * appends it automatically.
     *
     * @param baseline   snapshots from the normal single-dependency scenario
     * @param amplified  snapshots from the fan-out tail-amplification scenario
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written to disk
     */
    public static void saveComparisonChart(
            List<PercentileSnapshot> baseline,
            List<PercentileSnapshot> amplified,
            Path outputPath) throws IOException {

        if (baseline.isEmpty() || amplified.isEmpty()) {
            return;
        }

        int count = Math.min(baseline.size(), amplified.size());
        double[] xData = new double[count];
        double[] baselineP99 = new double[count];
        double[] amplifiedP99 = new double[count];

        for (int i = 0; i < count; i++) {
            xData[i] = (double) baseline.get(i).elapsedSeconds();
            baselineP99[i] = baseline.get(i).p99Ms();
            amplifiedP99[i] = amplified.get(i).p99Ms();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Tail Amplification: p99 Normal vs Fan-Out")
                .xAxisTitle("Elapsed (seconds)")
                .yAxisTitle("Latency (ms)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Normal p99", xData, baselineP99).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Fan-Out p99", xData, amplifiedP99).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
