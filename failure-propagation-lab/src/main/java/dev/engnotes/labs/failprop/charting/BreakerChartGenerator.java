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

import dev.engnotes.labs.failprop.breaker.BreakerRunResult;
import dev.engnotes.labs.failprop.breaker.BreakerWindowSample;
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
 * Generates XChart PNGs from a circuit-breaker run. PNGs are visual references for the blog
 * post; the CSV is the golden-file contract.
 */
public final class BreakerChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private BreakerChartGenerator() {}

    /**
     * Route-b success through the transient degradation, naive vs breakered. Route-b never
     * touches the database; under naive retries it dies anyway (Post 1's shared-pool cascade,
     * amplified by Post 2's hangs). The breaker fails fast, releases the shared workers, and
     * route-b barely notices.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveBlastRadiusChart(BreakerRunResult result, Path outputPath) throws IOException {
        saveTimelineChart(result,
                "Route-b Success Through the Degradation - The Blast Radius",
                "Success Rate (%)",
                BreakerWindowSample::naiveRouteBPct, "naive retries",
                BreakerWindowSample::breakerRouteBPct, "with breakers",
                LegendPosition.InsideSW,
                outputPath);
    }

    /**
     * Database attempt rate, naive vs breakered. Post 2's storm (the 6x spike) collapses to a
     * probe trickle once the breakers trip.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveStormSuppressionChart(BreakerRunResult result, Path outputPath) throws IOException {
        saveTimelineChart(result,
                "Database Attempt Rate - The Storm, Suppressed",
                "Database Attempts (rps)",
                BreakerWindowSample::naiveDbAttemptsRps, "naive retries",
                BreakerWindowSample::breakerDbAttemptsRps, "with breakers",
                LegendPosition.InsideNE,
                outputPath);
    }

    private static void saveTimelineChart(
            BreakerRunResult result,
            String title,
            String yAxisTitle,
            ToDoubleFunction<BreakerWindowSample> first, String firstLabel,
            ToDoubleFunction<BreakerWindowSample> second, String secondLabel,
            LegendPosition legend,
            Path outputPath) throws IOException {

        List<BreakerWindowSample> timeline = result.timeline();
        if (timeline.isEmpty()) {
            return;
        }

        double[] windowStartS = new double[timeline.size()];
        double[] a = new double[timeline.size()];
        double[] b = new double[timeline.size()];
        for (int i = 0; i < timeline.size(); i++) {
            windowStartS[i] = timeline.get(i).windowStartMs() / 1000.0;
            a[i] = first.applyAsDouble(timeline.get(i));
            b[i] = second.applyAsDouble(timeline.get(i));
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title(title)
                .xAxisTitle("Time (s)")
                .yAxisTitle(yAxisTitle)
                .build();

        chart.getStyler().setLegendPosition(legend);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries(firstLabel, windowStartS, a).setMarker(SeriesMarkers.NONE);
        chart.addSeries(secondLabel, windowStartS, b).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
