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
package dev.engnotes.labs.backpressure.shaping;

import java.util.OptionalDouble;

/**
 * Leaky-bucket rate gate: <em>shaping</em>. Admitted arrivals are released downstream at most
 * one per leak interval, so the output rate never exceeds the leak rate no matter how bursty the
 * input is - the burst is absorbed as <em>delay at the gate</em>. An arrival that would have to
 * wait behind {@code queueCapacity} or more pending releases is rejected (fail-fast).
 *
 * <p>{@code queueCapacity} is the design knob this experiment sweeps: it bounds how long an
 * admitted request can be held at the gate ({@code queueCapacity x leak interval}), and
 * therefore the worst-case gate delay.
 */
public final class LeakyBucketGate implements RateGate {

    private final int queueCapacity;
    private final double leakIntervalMs;
    private double nextReleaseMs;

    public LeakyBucketGate(double ratePerSecond, int queueCapacity) {
        if (ratePerSecond <= 0 || queueCapacity < 1) {
            throw new IllegalArgumentException("ratePerSecond must be > 0 and queueCapacity >= 1");
        }
        this.queueCapacity = queueCapacity;
        this.leakIntervalMs = 1000.0 / ratePerSecond;
        this.nextReleaseMs = 0.0;
    }

    @Override
    public OptionalDouble offer(double arrivalMs) {
        if (pendingAhead(arrivalMs) >= queueCapacity) {
            return OptionalDouble.empty();
        }
        double releaseMs = Math.max(arrivalMs, nextReleaseMs);
        nextReleaseMs = releaseMs + leakIntervalMs;
        return OptionalDouble.of(releaseMs);
    }

    private long pendingAhead(double arrivalMs) {
        if (nextReleaseMs <= arrivalMs) {
            return 0L;
        }
        return (long) Math.ceil((nextReleaseMs - arrivalMs) / leakIntervalMs);
    }
}
