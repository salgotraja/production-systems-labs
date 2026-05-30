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

public final class CoordinatedOmissionChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private CoordinatedOmissionChartGenerator() {}

    public static void saveP99ComparisonChart(
            List<PercentileSnapshot> closedLoopRaw,
            List<PercentileSnapshot> closedLoopCorrected,
            List<PercentileSnapshot> openLoop,
            Path outputPath) throws IOException {

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Coordinated Omission - p99 Comparison")
                .xAxisTitle("Elapsed (seconds)")
                .yAxisTitle("p99 Latency (ms)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        addP99Series(chart, "Closed-loop raw", closedLoopRaw);
        addP99Series(chart, "Closed-loop corrected", closedLoopCorrected);
        addP99Series(chart, "Open-loop", openLoop);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    public static void saveThroughputChart(
            List<PercentileSnapshot> closedLoopRaw,
            List<PercentileSnapshot> openLoop,
            Path outputPath) throws IOException {

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Closed-loop Stops Sending During the Pause")
                .xAxisTitle("Elapsed (seconds)")
                .yAxisTitle("Measured Throughput (rps)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        addThroughputSeries(chart, "Closed-loop raw", closedLoopRaw);
        addThroughputSeries(chart, "Open-loop", openLoop);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static void addP99Series(XYChart chart, String name, List<PercentileSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        int count = snapshots.size();
        double[] xData = new double[count];
        double[] yData = new double[count];
        for (int i = 0; i < count; i++) {
            xData[i] = snapshots.get(i).elapsedSeconds();
            yData[i] = snapshots.get(i).p99Ms();
        }
        chart.addSeries(name, xData, yData).setMarker(SeriesMarkers.NONE);
    }

    private static void addThroughputSeries(XYChart chart, String name, List<PercentileSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        int count = snapshots.size();
        double[] xData = new double[count];
        double[] yData = new double[count];
        for (int i = 0; i < count; i++) {
            xData[i] = snapshots.get(i).elapsedSeconds();
            yData[i] = snapshots.get(i).throughputRps();
        }
        chart.addSeries(name, xData, yData).setMarker(SeriesMarkers.NONE);
    }
}
