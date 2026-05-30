/*
 * Copyright 2026 engnotes.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.engnotes.labs.latency.simulation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyInjectorTest {

    private static final long NORMAL_MIN_MS = 1L;
    private static final long TAIL_MAX_MS = 3_000L;

    @Test
    void sameSeedProducesSameSequence() {
        List<Long> a = sample(new LatencyInjector(true, 42L), 1_000);
        List<Long> b = sample(new LatencyInjector(true, 42L), 1_000);
        assertEquals(a, b, "deterministic mode with the same seed must reproduce the sequence");
    }

    @Test
    void valuesAreClampedToDocumentedRanges() {
        LatencyInjector injector = new LatencyInjector(true, 42L);
        for (long v : sample(injector, 10_000)) {
            assertTrue(v >= NORMAL_MIN_MS && v <= TAIL_MAX_MS, "out of documented range: " + v);
        }
    }

    private static List<Long> sample(LatencyInjector injector, int n) {
        List<Long> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(injector.sampleLatencyMs());
        }
        return out;
    }
}
