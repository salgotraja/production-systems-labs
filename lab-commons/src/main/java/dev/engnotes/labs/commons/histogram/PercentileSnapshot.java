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
package dev.engnotes.labs.commons.histogram;

import org.HdrHistogram.Histogram;

/**
 * Immutable snapshot of key percentile values extracted from a {@link Histogram}.
 * Carries the data needed for one CSV row and one terminal table row.
 */
public record PercentileSnapshot(
        long timestampMs,
        long elapsedSeconds,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double p999Ms,
        double throughputRps,
        long errorCount,
        long totalRequests) {

    /**
     * Extracts a {@code PercentileSnapshot} from a completed interval histogram.
     *
     * @param histogram      the interval histogram to read from (not modified)
     * @param startMs        wall-clock time when the experiment started (epoch millis)
     * @param elapsedSeconds seconds elapsed since experiment start
     * @param throughputRps  requests completed per second in this interval
     * @param errorCount     errors recorded in this interval
     * @param totalRequests  cumulative request count across all intervals
     */
    public static PercentileSnapshot from(
            Histogram histogram,
            long startMs,
            long elapsedSeconds,
            double throughputRps,
            long errorCount,
            long totalRequests) {

        return new PercentileSnapshot(
                startMs + (elapsedSeconds * 1000L),
                elapsedSeconds,
                histogram.getValueAtPercentile(50.0),
                histogram.getValueAtPercentile(95.0),
                histogram.getValueAtPercentile(99.0),
                histogram.getValueAtPercentile(99.9),
                throughputRps,
                errorCount,
                totalRequests);
    }
}
