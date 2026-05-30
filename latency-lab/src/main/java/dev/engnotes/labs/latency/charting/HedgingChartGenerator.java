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
import dev.engnotes.labs.latency.hedging.HedgeCostPoint;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler.LegendPosition;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class HedgingChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private HedgingChartGenerator() {}

    public static void saveLatencyComparisonChart(
            List<PercentileSnapshot> baseline,
            List<PercentileSnapshot> hedged,
            String thresholdLabel,
            Path outputPath) throws IOException {

        if (baseline.isEmpty() || hedged.isEmpty()) {
            return;
        }

        int count = Math.min(baseline.size(), hedged.size());
        double[] xData = new double[count];
        double[] baselineP99 = new double[count];
        double[] hedgedP99 = new double[count];

        for (int i = 0; i < count; i++) {
            xData[i] = baseline.get(i).elapsedSeconds();
            baselineP99[i] = baseline.get(i).p99Ms();
            hedgedP99[i] = hedged.get(i).p99Ms();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Hedging p99 Before vs After (" + thresholdLabel + ")")
                .xAxisTitle("Elapsed (seconds)")
                .yAxisTitle("Latency (ms)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("No hedge p99", xData, baselineP99).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Hedged p99", xData, hedgedP99).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    public static void saveCostChart(List<HedgeCostPoint> points, Path outputPath) throws IOException {
        if (points.isEmpty()) {
            return;
        }

        List<String> labels = points.stream().map(point -> point.threshold().label()).toList();
        List<Double> improvement = points.stream().map(HedgeCostPoint::p99ImprovementPct).toList();
        List<Double> extraLoad = points.stream().map(HedgeCostPoint::extraLoadPct).toList();

        CategoryChart chart = new CategoryChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Tail Improvement vs Extra Load")
                .xAxisTitle("Hedge threshold")
                .yAxisTitle("Percent")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.addSeries("p99 improvement %", labels, improvement);
        chart.addSeries("extra load %", labels, extraLoad);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
