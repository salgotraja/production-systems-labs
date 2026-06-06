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

import dev.engnotes.labs.backpressure.shedding.ShedPointResult;
import dev.engnotes.labs.backpressure.shedding.ShedRunResult;
import dev.engnotes.labs.backpressure.shedding.ShedWindowSample;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates XChart PNGs from a load-shedding run. PNGs are visual references; the CSVs are the
 * golden-file contract. Both charts use a log latency axis (values floored at 1ms for the log
 * scale only - the CSVs keep the exact values) because FIFO's collapse lives orders of magnitude
 * above the policies that shed.
 */
public final class SheddingChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;
    private static final double LOG_FLOOR_MS = 1.0;

    private SheddingChartGenerator() {}

    /**
     * The hero chart: p99 of completed work per 100ms window over the burst curve. FIFO keeps
     * paying for each spike long after it ended; LIFO stays fresh throughout; tail-drop and
     * expire cap the damage near the deadline.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveHangoverChart(ShedRunResult result, Path outputPath) throws IOException {
        List<ShedWindowSample> windows = result.hangover();
        if (windows.isEmpty()) {
            return;
        }

        double[] time = new double[windows.size()];
        double[] fifo = new double[windows.size()];
        double[] tailDrop = new double[windows.size()];
        double[] expire = new double[windows.size()];
        double[] lifo = new double[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            time[i] = windows.get(i).windowStartMs() / 1000.0;
            fifo[i] = floored(windows.get(i).fifoP99Ms());
            tailDrop[i] = floored(windows.get(i).tailDropP99Ms());
            expire[i] = floored(windows.get(i).expireP99Ms());
            lifo[i] = floored(windows.get(i).lifoP99Ms());
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("The Burst Hangover - p99 of Served Work Over Time")
                .xAxisTitle("Time (s)")
                .yAxisTitle("p99 of Served Work (ms, log)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setYAxisLogarithmic(true);

        chart.addSeries("fifo (no shedding)", time, fifo).setMarker(SeriesMarkers.NONE);
        // tail-drop and expire serve the same work, so their lines coincide - draw expire first
        // and dash tail-drop on top so both stay visible.
        chart.addSeries("expire", time, expire).setMarker(SeriesMarkers.NONE);
        chart.addSeries("tail-drop", time, tailDrop).setMarker(SeriesMarkers.NONE)
                .setLineStyle(SeriesLines.DASH_DASH);
        chart.addSeries("lifo", time, lifo).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * The sweep view: p99 of served work vs offered load per policy. FIFO explodes past
     * capacity; tail-drop and expire serve near-deadline work (they overlap - their difference
     * is the shed wait, see the CSV); LIFO serves fresh work at any load.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveSweepChart(ShedRunResult result, Path outputPath) throws IOException {
        Map<String, List<ShedPointResult>> byPolicy = new LinkedHashMap<>();
        for (ShedPointResult point : result.sweep()) {
            byPolicy.computeIfAbsent(point.policy(), key -> new ArrayList<>()).add(point);
        }
        if (byPolicy.isEmpty()) {
            return;
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("p99 of Served Work vs Offered Load")
                .xAxisTitle("Offered Load (rps)")
                .yAxisTitle("p99 of Served Work (ms, log)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setYAxisLogarithmic(true);

        // tail-drop and expire serve the same work, so their lines coincide - draw expire before
        // tail-drop and dash the latter so both stay visible.
        for (String policy : List.of("fifo", "expire", "tail-drop", "lifo")) {
            List<ShedPointResult> points = byPolicy.get(policy);
            if (points == null) {
                continue;
            }
            double[] offered = new double[points.size()];
            double[] p99 = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                offered[i] = points.get(i).offeredRps();
                p99[i] = floored(points.get(i).p99ServedMs());
            }
            XYSeries series = chart.addSeries(policy, offered, p99);
            series.setMarker(SeriesMarkers.CIRCLE);
            if ("tail-drop".equals(policy)) {
                series.setLineStyle(SeriesLines.DASH_DASH);
            }
        }

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    private static double floored(double valueMs) {
        return Math.max(LOG_FLOOR_MS, valueMs);
    }
}
