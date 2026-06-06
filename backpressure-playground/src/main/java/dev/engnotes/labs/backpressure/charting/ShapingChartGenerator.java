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

import dev.engnotes.labs.backpressure.shaping.ShapingPointResult;
import dev.engnotes.labs.backpressure.shaping.ShapingRunResult;
import dev.engnotes.labs.backpressure.shaping.ShapingWindowSample;
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
 * Generates XChart PNGs from a token-bucket vs leaky-bucket run. PNGs are visual references; the
 * CSVs are the golden-file contract.
 */
public final class ShapingChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private ShapingChartGenerator() {}

    /**
     * Downstream rate over time at the sweet-spot burst dimension: shaping vs policing in one
     * picture. The token bucket passes the offered spike through at line rate; the leaky bucket
     * releases a flat stream at the leak rate.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveShapingChart(ShapingRunResult result, Path outputPath) throws IOException {
        List<ShapingWindowSample> windows = result.windows();
        if (windows.isEmpty()) {
            return;
        }

        double[] time = new double[windows.size()];
        double[] offered = new double[windows.size()];
        double[] token = new double[windows.size()];
        double[] leaky = new double[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            time[i] = windows.get(i).windowStartMs() / 1000.0;
            offered[i] = windows.get(i).offeredRps();
            token[i] = windows.get(i).tokenBucketRps();
            leaky[i] = windows.get(i).leakyBucketRps();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Downstream Rate - Policing Passes the Burst, Shaping Flattens It")
                .xAxisTitle("Time (s)")
                .yAxisTitle("Rate (rps)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Offered", time, offered).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Token bucket (policing)", time, token).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Leaky bucket (shaping)", time, leaky).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * The burst the server actually sees as the burst dimension grows: the token bucket's
     * downstream peak climbs with the bucket size, the leaky bucket's stays pinned at the leak
     * rate. The displaced wait is the same in magnitude on both sides (see the
     * {@code gate_delay_p99_ms} / {@code server_wait_p99_ms} CSV columns - they mirror exactly),
     * so the chart shows the dimension where the gates genuinely diverge.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveBurstSweepChart(ShapingRunResult result, Path outputPath) throws IOException {
        List<ShapingPointResult> token = result.tokenSweep();
        List<ShapingPointResult> leaky = result.leakySweep();
        if (token.isEmpty()) {
            return;
        }

        double[] burst = new double[token.size()];
        double[] tokenPeak = new double[token.size()];
        double[] leakyPeak = new double[leaky.size()];
        double[] capacity = new double[token.size()];
        for (int i = 0; i < token.size(); i++) {
            burst[i] = token.get(i).burstCapacity();
            tokenPeak[i] = token.get(i).downstreamPeakRps();
            leakyPeak[i] = leaky.get(i).downstreamPeakRps();
            capacity[i] = result.serverCapacityRps();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Peak Downstream Rate - the Burst the Server Sees (budget = "
                        + result.burstSweetSpot() + ")")
                .xAxisTitle("Burst Dimension (bucket size B / queue depth Q)")
                .yAxisTitle("Peak Downstream Rate (rps, 100ms windows)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Token bucket (policing)", burst, tokenPeak).setMarker(SeriesMarkers.CIRCLE);
        chart.addSeries("Leaky bucket (shaping)", burst, leakyPeak).setMarker(SeriesMarkers.DIAMOND);
        chart.addSeries("Server capacity", burst, capacity).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
