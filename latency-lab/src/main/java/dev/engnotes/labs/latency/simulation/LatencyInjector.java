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
package dev.engnotes.labs.latency.simulation;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Models a bimodal latency distribution used to simulate realistic request latency:
 * the majority of requests follow a normal distribution around a fast mean, while
 * a small fraction are "tail" requests drawn from an exponential distribution to
 * simulate database slow-queries, GC pauses, and other production tail causes.
 *
 * <p>Thread-safe: each thread maintains its own {@link Random} via {@link ThreadLocal},
 * so no synchronization is required.
 *
 * <p>In deterministic mode each virtual thread receives a unique seed derived from
 * {@value #DETERMINISTIC_SEED} via an {@link AtomicLong} counter. This ensures different
 * threads produce different latency sequences (necessary to observe tail amplification)
 * while keeping overall output reproducible across runs.
 */
public final class LatencyInjector {

    /** Probability that a given request is a slow tail request (1%). */
    private static final double TAIL_PROBABILITY = 0.01;

    /** Mean latency for the normal (fast) distribution, in milliseconds. */
    private static final double NORMAL_MEAN_MS = 20.0;

    /** Standard deviation for the normal (fast) distribution, in milliseconds. */
    private static final double NORMAL_STDDEV_MS = 5.0;

    /**
     * Mean of the exponential distribution used to generate tail spike durations,
     * in milliseconds.
     */
    private static final double TAIL_MEAN_MS = 500.0;

    /** Base seed for deterministic runs. Thread N receives seed {@code DETERMINISTIC_SEED + N}. */
    private static final long DETERMINISTIC_SEED = 42L;

    /** Minimum clamped value for the normal distribution, in milliseconds. */
    private static final long NORMAL_MIN_MS = 1L;

    /** Maximum clamped value for the normal distribution, in milliseconds. */
    private static final long NORMAL_MAX_MS = 100L;

    /** Minimum clamped value for tail spikes, in milliseconds. */
    private static final long TAIL_MIN_MS = 200L;

    /** Maximum clamped value for tail spikes, in milliseconds. */
    private static final long TAIL_MAX_MS = 3_000L;

    private final ThreadLocal<Random> threadLocalRandom;

    /**
     * Creates a new {@code LatencyInjector}.
     *
     * @param deterministic if {@code true}, each thread receives a unique seed derived from
     *                      {@value #DETERMINISTIC_SEED} so output is reproducible across runs
     *                      while each thread samples a distinct sequence; if {@code false},
     *                      each thread gets an independently seeded {@link Random}
     */
    public LatencyInjector(boolean deterministic) {
        if (deterministic) {
            AtomicLong seedCounter = new AtomicLong(DETERMINISTIC_SEED);
            this.threadLocalRandom = ThreadLocal.withInitial(
                    () -> new Random(seedCounter.getAndIncrement()));
        } else {
            this.threadLocalRandom = ThreadLocal.withInitial(Random::new);
        }
    }

    /**
     * Samples a single latency value from the bimodal distribution.
     *
     * <p>With probability {@value #TAIL_PROBABILITY}, returns a tail spike drawn from
     * an exponential distribution (mean {@value #TAIL_MEAN_MS} ms), clamped to
     * [{@value #TAIL_MIN_MS}, {@value #TAIL_MAX_MS}] ms. Otherwise returns a value
     * drawn from a normal distribution (mean {@value #NORMAL_MEAN_MS} ms, stddev
     * {@value #NORMAL_STDDEV_MS} ms), clamped to
     * [{@value #NORMAL_MIN_MS}, {@value #NORMAL_MAX_MS}] ms.
     *
     * @return sampled latency in milliseconds (always positive)
     */
    public long sampleLatencyMs() {
        Random random = threadLocalRandom.get();

        if (random.nextDouble() < TAIL_PROBABILITY) {
            double tailSample = -TAIL_MEAN_MS * Math.log(1.0 - random.nextDouble());
            long clamped = Math.round(tailSample);
            return Math.max(TAIL_MIN_MS, Math.min(TAIL_MAX_MS, clamped));
        } else {
            double normalSample = random.nextGaussian() * NORMAL_STDDEV_MS + NORMAL_MEAN_MS;
            long clamped = Math.round(normalSample);
            return Math.max(NORMAL_MIN_MS, Math.min(NORMAL_MAX_MS, clamped));
        }
    }
}
