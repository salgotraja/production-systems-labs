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
package dev.engnotes.labs.backpressure.admission;

import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic, piecewise-constant offered-load schedule. Segments are tiled in order to fill
 * the run window, so the same curve reproduces byte-identical arrival times every run.
 *
 * <p>A time-varying curve is what gives admission control a real design tradeoff: under a tight
 * concurrency limit, burst traffic is rejected that the server could have absorbed during the
 * following valley, so a too-tight limit wastes capacity. Under sustained uniform load there is
 * no such tradeoff (a tighter limit is simply better), which is why this experiment runs over
 * bursts rather than a flat rate.
 */
public final class DemandCurve {

    /** One constant-rate phase of the curve. */
    public record Segment(double rateRps, long durationMs) {
        public Segment {
            if (rateRps < 0) {
                throw new IllegalArgumentException("rateRps must be >= 0");
            }
            if (durationMs <= 0) {
                throw new IllegalArgumentException("durationMs must be > 0");
            }
        }
    }

    private final List<Segment> segments;

    public DemandCurve(List<Segment> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("curve needs at least one segment");
        }
        this.segments = List.copyOf(segments);
    }

    /** A flat curve at {@code rateRps} for the whole window. */
    public static DemandCurve constant(double rateRps) {
        return new DemandCurve(List.of(new Segment(rateRps, Long.MAX_VALUE)));
    }

    /**
     * A bursty curve: {@code valleyRps} for {@code valleyMs}, then {@code spikeRps} for
     * {@code spikeMs}, repeating. Average load sits near capacity while peaks run well above it.
     */
    public static DemandCurve bursty(
            double valleyRps, long valleyMs, double spikeRps, long spikeMs) {
        return new DemandCurve(List.of(
                new Segment(valleyRps, valleyMs),
                new Segment(spikeRps, spikeMs)));
    }

    /**
     * Generates deterministic arrival timestamps (ms) across {@code [0, windowMs)}, tiling the
     * segments in order. Within a segment, arrivals are evenly spaced at {@code 1000 / rate}.
     */
    public double[] arrivalTimesMs(long windowMs) {
        List<Double> times = new ArrayList<>();
        long segmentStart = 0L;
        int index = 0;
        while (segmentStart < windowMs) {
            Segment segment = segments.get(index % segments.size());
            long segmentEnd = clampedEnd(segmentStart, segment.durationMs(), windowMs);
            if (segment.rateRps() > 0) {
                double spacingMs = 1000.0 / segment.rateRps();
                for (double t = segmentStart; t < segmentEnd; t += spacingMs) {
                    times.add(t);
                }
            }
            segmentStart = segmentEnd;
            index++;
        }
        double[] result = new double[times.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = times.get(i);
        }
        return result;
    }

    private static long clampedEnd(long start, long duration, long windowMs) {
        // Guard against overflow for the constant curve's Long.MAX_VALUE duration.
        if (duration >= windowMs - start) {
            return windowMs;
        }
        return start + duration;
    }
}
