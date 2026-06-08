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

import dev.engnotes.labs.failprop.bulkhead.BulkheadPolicyPoint;
import dev.engnotes.labs.failprop.bulkhead.BulkheadRunResult;
import dev.engnotes.labs.failprop.bulkhead.BulkheadSweepPoint;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Generates XChart PNGs from a failure-isolation run. PNGs are visual references for the blog
 * post; the CSV is the golden-file contract.
 */
public final class BulkheadChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private BulkheadChartGenerator() {}

    /**
     * Both routes' success by policy. route-b is flat on the floor for naive / breaker / budget
     * (the detection-based tools are blind to a slow-but-healthy neighbour) and only the
     * bulkhead lifts it - the capstone's headline.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void savePolicyChart(BulkheadRunResult result, Path outputPath) throws IOException {
        if (result.policies().isEmpty()) {
            return;
        }
        List<String> policies = new ArrayList<>();
        List<Double> routeA = new ArrayList<>();
        List<Double> routeB = new ArrayList<>();
        for (BulkheadPolicyPoint p : result.policies()) {
            policies.add(p.policy());
            routeA.add(p.routeASuccessPct());
            routeB.add(p.routeBSuccessPct());
        }

        CategoryChart chart = new CategoryChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Success by Policy - Only the Bulkhead Saves the Victim")
                .xAxisTitle("Policy")
                .yAxisTitle("Success Rate (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNW);
        chart.getStyler().setAvailableSpaceFill(0.6);
        chart.addSeries("route-a (the slow neighbour)", policies, routeA);
        chart.addSeries("route-b (the victim)", policies, routeB);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * The bulkhead sizing trade-off: route-b's success against route-b's reserved slice (flat at
     * 100 once its small need is met), and route-a's success falling as the reserve grows - the
     * cost of isolation is the borrowing route-a forgoes.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveSizingChart(BulkheadRunResult result, Path outputPath) throws IOException {
        if (result.sizing().isEmpty()) {
            return;
        }
        double[] reserve = new double[result.sizing().size()];
        double[] routeA = new double[result.sizing().size()];
        double[] routeB = new double[result.sizing().size()];
        for (int i = 0; i < result.sizing().size(); i++) {
            BulkheadSweepPoint p = result.sizing().get(i);
            reserve[i] = p.routeBReserved();
            routeA[i] = p.routeASuccessPct();
            routeB[i] = p.routeBSuccessPct();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Bulkhead Sizing - The Cost of Isolation Is Forgone Borrowing")
                .xAxisTitle("Workers Reserved for route-b")
                .yAxisTitle("Success Rate (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.OutsideE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.addSeries("route-a (the slow neighbour)", reserve, routeA).setMarker(SeriesMarkers.CIRCLE);
        chart.addSeries("route-b (the victim)", reserve, routeB).setMarker(SeriesMarkers.DIAMOND);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
