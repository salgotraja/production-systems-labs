/*
 * Copyright 2026 engnotes.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.engnotes.labs.commons;

import dev.engnotes.labs.commons.histogram.LatencyHistogram;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyHistogramTest {

    @Test
    void recordsValuesIntoInterval() {
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 0; i < 100; i++) {
            h.recordLatency(10L);
        }
        Histogram interval = h.intervalHistogram();
        assertEquals(100, interval.getTotalCount());
        assertEquals(10L, interval.getValueAtPercentile(50.0));
    }

    @Test
    void clipsValuesAboveMax() {
        LatencyHistogram h = new LatencyHistogram();
        h.recordLatency(100_000L); // above the 60s cap
        Histogram interval = h.intervalHistogram();
        assertEquals(1, interval.getTotalCount());
        // The recorded value must be clipped to ~60s, never the original 100s.
        assertTrue(interval.getMaxValue() <= 60_100L,
                "values above 60s must be clipped to 60s, got " + interval.getMaxValue());
    }

    @Test
    void intervalFlipResetsAccumulator() {
        LatencyHistogram h = new LatencyHistogram();
        h.recordLatency(10L);
        h.intervalHistogram(); // first flip consumes the interval
        Histogram second = h.intervalHistogram();
        assertEquals(0, second.getTotalCount(),
                "second interval call must be empty - the first flip consumed the data");
    }
}
