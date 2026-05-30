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

import dev.engnotes.labs.latency.slo.ArchitecturePattern;
import dev.engnotes.labs.latency.slo.SloSummary;
import dev.engnotes.labs.latency.slo.SloWindow;
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

public final class SloChartGenerator {

    private static final int CHART_WIDTH = 900;
    private static final int CHART_HEIGHT = 420;

    private SloChartGenerator() {}

    public static void saveBurnRateChart(List<SloWindow> windows, Path outputPath) throws IOException {
        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Instantaneous Burn Rate")
                .xAxisTitle("Elapsed (seconds)")
                .yAxisTitle("Burn rate x")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        for (ArchitecturePattern pattern : ArchitecturePattern.values()) {
            addBurnRateSeries(chart, pattern, windows);
        }

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    public static void saveBudgetChart(List<SloSummary> summaries, Path outputPath) throws IOException {
        if (summaries.isEmpty()) {
            return;
        }

        List<String> labels = summaries.stream().map(summary -> summary.pattern().label()).toList();
        List<Double> achievedSli = summaries.stream().map(SloSummary::achievedSliPct).toList();
        List<Double> remainingBudget = summaries.stream().map(SloSummary::finalBudgetRemainingPct).toList();

        CategoryChart chart = new CategoryChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("SLI and Remaining Error Budget")
                .xAxisTitle("Pattern")
                .yAxisTitle("Percent")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideSE);
        chart.addSeries("achieved SLI %", labels, achievedSli);
        chart.addSeries("remaining budget %", labels, remainingBudget);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static void addBurnRateSeries(XYChart chart, ArchitecturePattern pattern, List<SloWindow> windows) {
        List<SloWindow> selected = windows.stream()
                .filter(window -> window.pattern() == pattern)
                .toList();
        if (selected.isEmpty()) {
            return;
        }

        double[] xData = new double[selected.size()];
        double[] yData = new double[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            xData[i] = selected.get(i).elapsedSeconds();
            yData[i] = selected.get(i).burnRate();
        }
        chart.addSeries(pattern.label(), xData, yData).setMarker(SeriesMarkers.NONE);
    }
}
