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

import dev.engnotes.labs.latency.backpressure.BackpressureSummary;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.style.Styler.LegendPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BackpressureChartGenerator {

    private static final int CHART_WIDTH = 900;
    private static final int CHART_HEIGHT = 420;

    private BackpressureChartGenerator() {}

    public static void saveAcceptanceChart(List<BackpressureSummary> summaries, Path outputPath) throws IOException {
        if (summaries.isEmpty()) {
            return;
        }

        List<String> labels = summaries.stream().map(summary -> summary.strategy().label()).toList();
        List<Long> accepted = summaries.stream().map(BackpressureSummary::acceptedRequests).toList();
        List<Long> rejected = summaries.stream().map(BackpressureSummary::rejectedRequests).toList();

        CategoryChart chart = new CategoryChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Accepted vs Rejected Requests")
                .xAxisTitle("Strategy")
                .yAxisTitle("Requests")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.addSeries("accepted", labels, accepted);
        chart.addSeries("rejected", labels, rejected);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    public static void saveLatencyChart(List<BackpressureSummary> summaries, Path outputPath) throws IOException {
        if (summaries.isEmpty()) {
            return;
        }

        List<String> labels = summaries.stream().map(summary -> summary.strategy().label()).toList();
        List<Double> p50 = summaries.stream().map(BackpressureSummary::p50Ms).toList();
        List<Double> p99 = summaries.stream().map(BackpressureSummary::p99Ms).toList();

        CategoryChart chart = new CategoryChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Accepted Request Latency")
                .xAxisTitle("Strategy")
                .yAxisTitle("Latency (ms)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.addSeries("p50", labels, p50);
        chart.addSeries("p99", labels, p99);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
