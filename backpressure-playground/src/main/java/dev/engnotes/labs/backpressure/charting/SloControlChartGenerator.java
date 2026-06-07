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

import dev.engnotes.labs.backpressure.slocontrol.SloPointResult;
import dev.engnotes.labs.backpressure.slocontrol.SloRunResult;
import dev.engnotes.labs.backpressure.slocontrol.SloWindowSample;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler.LegendPosition;
import org.knowm.xchart.style.lines.SeriesLines;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Generates XChart PNGs from an SLO-driven load-control run. PNGs are visual references; the
 * CSVs are the golden-file contract.
 */
public final class SloControlChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private SloControlChartGenerator() {}

    /**
     * The hero chart: per-class success rate through the bursts. Blind's critical line dives
     * with every spike; priority's stays pinned at ~100 while its background line pays the bill.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveBurstChart(SloRunResult result, Path outputPath) throws IOException {
        List<SloWindowSample> windows = result.windows();
        if (windows.isEmpty()) {
            return;
        }

        double[] time = new double[windows.size()];
        double[] blindCritical = new double[windows.size()];
        double[] priorityCritical = new double[windows.size()];
        double[] priorityBackground = new double[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            time[i] = windows.get(i).windowStartMs() / 1000.0;
            blindCritical[i] = windows.get(i).blindCriticalPct();
            priorityCritical[i] = windows.get(i).priorityCriticalPct();
            priorityBackground[i] = windows.get(i).priorityBackgroundPct();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Critical Success Rate Through Bursts - Blind vs Priority")
                .xAxisTitle("Time (s)")
                .yAxisTitle("Success Rate (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.OutsideS);
        chart.getStyler().setLegendLayout(org.knowm.xchart.style.Styler.LegendLayout.Horizontal);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setYAxisMin(0.0);
        chart.getStyler().setYAxisMax(105.0);

        chart.addSeries("critical (priority)", time, priorityCritical).setMarker(SeriesMarkers.NONE);
        chart.addSeries("critical (blind)", time, blindCritical).setMarker(SeriesMarkers.NONE);
        chart.addSeries("background (priority)", time, priorityBackground).setMarker(SeriesMarkers.NONE)
                .setLineStyle(SeriesLines.DASH_DASH);
        chart.addSeries("SLO target", time, constant(time.length, result.sloTargetPct()))
                .setMarker(SeriesMarkers.NONE).setLineStyle(SeriesLines.DOT_DOT);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * The sweep view: critical success rate vs offered load. Blind loses the SLO at the first
     * overload; priority holds ~100 until the protection ceiling, where critical traffic alone
     * exceeds capacity.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveProtectionChart(SloRunResult result, Path outputPath) throws IOException {
        List<SloPointResult> blind = byPolicy(result, "blind");
        List<SloPointResult> priority = byPolicy(result, "priority");
        if (blind.isEmpty()) {
            return;
        }

        double[] offered = new double[blind.size()];
        double[] blindCritical = new double[blind.size()];
        double[] priorityCritical = new double[priority.size()];
        double[] priorityBackground = new double[priority.size()];
        for (int i = 0; i < blind.size(); i++) {
            offered[i] = blind.get(i).offeredRps();
            blindCritical[i] = blind.get(i).criticalSuccessPct();
            priorityCritical[i] = priority.get(i).criticalSuccessPct();
            priorityBackground[i] = priority.get(i).backgroundSuccessPct();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Critical Success vs Offered Load (protection ceiling = "
                        + Math.round(result.protectionCeilingRps()) + " rps)")
                .xAxisTitle("Offered Load (rps)")
                .yAxisTitle("Success Rate (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.OutsideS);
        chart.getStyler().setLegendLayout(org.knowm.xchart.style.Styler.LegendLayout.Horizontal);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setYAxisMin(0.0);
        chart.getStyler().setYAxisMax(105.0);

        chart.addSeries("critical (priority)", offered, priorityCritical).setMarker(SeriesMarkers.CIRCLE);
        chart.addSeries("critical (blind)", offered, blindCritical).setMarker(SeriesMarkers.DIAMOND);
        chart.addSeries("background (priority)", offered, priorityBackground)
                .setMarker(SeriesMarkers.NONE).setLineStyle(SeriesLines.DASH_DASH);
        chart.addSeries("SLO target", offered, constant(offered.length, result.sloTargetPct()))
                .setMarker(SeriesMarkers.NONE).setLineStyle(SeriesLines.DOT_DOT);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static List<SloPointResult> byPolicy(SloRunResult result, String policy) {
        return result.sweep().stream().filter(point -> point.policy().equals(policy)).toList();
    }

    private static double[] constant(int length, double value) {
        double[] values = new double[length];
        Arrays.fill(values, value);
        return values;
    }
}
