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
import org.HdrHistogram.Recorder;

/**
 * Thread-safe thin wrapper around HdrHistogram for latency recording.
 * <p>
 * Configuration (fixed per ADR-001):
 * <ul>
 *   <li>3 significant value digits - 0.1% precision at all recorded values</li>
 *   <li>Highest trackable value: 60,000 ms (60 seconds) - values above are silently clipped</li>
 * </ul>
 * <p>
 * Uses {@link Recorder} for lock-free concurrent recording. Call {@link #intervalHistogram()}
 * to flip the accumulator and get a snapshot for the last interval. Snapshots are safe to
 * read from a single consumer thread while recording continues on any number of producer threads.
 */
public final class LatencyHistogram {

    /** Maximum trackable latency in milliseconds (60 seconds). Values above are silently clipped. */
    static final long MAX_LATENCY_MS = 60_000L;
    static final int SIGNIFICANT_VALUE_DIGITS = 3;

    private final Recorder recorder;

    public LatencyHistogram() {
        this.recorder = new Recorder(MAX_LATENCY_MS, SIGNIFICANT_VALUE_DIGITS);
    }

    /**
     * Records a latency value in milliseconds.
     * <p>
     * Thread-safe - may be called from any number of Virtual Threads concurrently.
     * Values above 60,000 ms are silently clipped to 60,000 ms.
     *
     * @param latencyMs latency to record, in milliseconds (must be non-negative)
     */
    public void recordLatency(long latencyMs) {
        recorder.recordValue(Math.min(latencyMs, MAX_LATENCY_MS));
    }

    /**
     * Flips the internal accumulator and returns a snapshot histogram covering the
     * period since the last call to this method (or since construction).
     * <p>
     * This method is NOT thread-safe with respect to other calls to itself -
     * call it from a single consumer/reporter thread only.
     *
     * @return interval histogram; caller owns it and may modify or discard it
     */
    public Histogram intervalHistogram() {
        return recorder.getIntervalHistogram();
    }

    /**
     * Returns a copy of the interval histogram corrected for coordinated omission,
     * given the expected inter-arrival time in milliseconds.
     * <p>
     * Use this in Post 4 to demonstrate correct open-loop measurement.
     * <p>
     * WARNING: this flips the underlying accumulator, exactly like
     * {@link #intervalHistogram()}. Call <em>either</em> this method <em>or</em>
     * {@link #intervalHistogram()} per interval, never both on the same instance -
     * the second call returns an empty histogram and the interval's data is lost.
     *
     * @param expectedIntervalMs expected time between requests in the open-loop scheduler
     * @return corrected histogram; caller owns it
     */
    public Histogram intervalHistogramCorrected(long expectedIntervalMs) {
        Histogram raw = recorder.getIntervalHistogram();
        return raw.copyCorrectedForCoordinatedOmission(expectedIntervalMs);
    }
}
