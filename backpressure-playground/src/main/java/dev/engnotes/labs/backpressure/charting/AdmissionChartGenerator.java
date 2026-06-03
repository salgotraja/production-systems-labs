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

import dev.engnotes.labs.backpressure.admission.AdmissionPointResult;
import dev.engnotes.labs.backpressure.admission.AdmissionRunResult;
import dev.engnotes.labs.backpressure.admission.AdmissionSimulator;
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
 * Generates XChart PNGs from an admission-control run. PNGs are visual references; the CSVs are
 * the golden-file contract.
 */
public final class AdmissionChartGenerator {

    private static final int CHART_WIDTH = 800;
    private static final int CHART_HEIGHT = 400;

    private AdmissionChartGenerator() {}

    /**
     * Goodput and server utilization vs the admission limit: the sweet-spot curve. Goodput peaks
     * near the Little's-Law limit; a tight limit leaves the server underutilized (rejecting burst
     * traffic the valley could serve), a loose limit collapses goodput as waits exceed the
     * deadline. The {@code none} (no-control) point is omitted so the x-axis stays a real limit.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void saveLimitSweepChart(AdmissionRunResult result, Path outputPath) throws IOException {
        List<AdmissionPointResult> finite = result.limitSweep().stream()
                .filter(p -> p.admissionLimit() != AdmissionSimulator.NO_LIMIT)
                .toList();
        if (finite.isEmpty()) {
            return;
        }

        double[] limit = new double[finite.size()];
        double[] goodput = new double[finite.size()];
        double[] utilization = new double[finite.size()];
        for (int i = 0; i < finite.size(); i++) {
            limit[i] = finite.get(i).admissionLimit();
            goodput[i] = finite.get(i).goodputRps();
            utilization[i] = finite.get(i).utilizationPct();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Choosing the Admission Limit (sweet spot near " + result.littlesLawLimit() + ")")
                .xAxisTitle("Admission Limit (max in-flight)")
                .yAxisTitle("Goodput (rps) / Utilization (%)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideSE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Goodput (rps)", limit, goodput).setMarker(SeriesMarkers.CIRCLE);
        chart.addSeries("Utilization (%)", limit, utilization).setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }

    /**
     * Goodput vs offered load with no control versus at the Little's-Law limit: Post 1's collapse
     * cliff restored to a plateau at capacity.
     *
     * @param result     the full run result
     * @param outputPath destination path without the {@code .png} extension
     * @throws IOException if the PNG cannot be written
     */
    public static void savePlateauChart(AdmissionRunResult result, Path outputPath) throws IOException {
        List<AdmissionPointResult> noControl = result.offeredNoControl();
        List<AdmissionPointResult> limited = result.offeredLimited();
        if (noControl.isEmpty()) {
            return;
        }

        double[] offered = new double[noControl.size()];
        double[] ideal = new double[noControl.size()];
        double[] goodputNoControl = new double[noControl.size()];
        double[] goodputLimited = new double[limited.size()];
        for (int i = 0; i < noControl.size(); i++) {
            offered[i] = noControl.get(i).offeredRps();
            ideal[i] = Math.min(noControl.get(i).offeredRps(), result.serverCapacityRps());
            goodputNoControl[i] = noControl.get(i).goodputRps();
            goodputLimited[i] = limited.get(i).goodputRps();
        }

        XYChart chart = new XYChartBuilder()
                .width(CHART_WIDTH)
                .height(CHART_HEIGHT)
                .title("Goodput vs Offered Load - Admission Control Restores the Plateau")
                .xAxisTitle("Offered Load (rps)")
                .yAxisTitle("Goodput (rps)")
                .build();

        chart.getStyler().setLegendPosition(LegendPosition.InsideNE);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

        chart.addSeries("Ideal", offered, ideal).setMarker(SeriesMarkers.NONE);
        chart.addSeries("No control (collapse)", offered, goodputNoControl).setMarker(SeriesMarkers.NONE);
        chart.addSeries("Admission limit = " + result.littlesLawLimit(), offered, goodputLimited)
                .setMarker(SeriesMarkers.NONE);

        Files.createDirectories(outputPath.getParent());
        BitmapEncoder.saveBitmap(chart, outputPath.toAbsolutePath().toString(), BitmapFormat.PNG);
    }
}
