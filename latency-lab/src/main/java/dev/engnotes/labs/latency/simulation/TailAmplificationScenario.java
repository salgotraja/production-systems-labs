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

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.concurrency.ScopedRunner;
import dev.engnotes.labs.commons.histogram.LatencyHistogram;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Demonstrates how fan-out amplifies tail latency: each top-level request fans out
 * to {@value #FAN_OUT_DEGREE} downstream services in parallel, and the observed
 * end-to-end response time is the <em>maximum</em> of all downstream latencies.
 *
 * <p>Even when each downstream service has only a 1% tail probability, the probability
 * that at least one of {@value #FAN_OUT_DEGREE} calls hits a tail spike is approximately
 * {@code 1 - (1 - 0.01)^5 ≈ 5%}. This scenario makes that amplification visible in the
 * recorded percentiles.
 */
public final class TailAmplificationScenario {

    /** Number of downstream services each top-level request fans out to. */
    private static final int FAN_OUT_DEGREE = 5;

    private final LatencyInjector downstreamInjector;
    private final LatencyHistogram histogram;
    private final boolean deterministic;

    /**
     * Creates a new {@code TailAmplificationScenario}.
     *
     * @param downstreamInjector provides sampled latency values for each downstream call
     * @param histogram          shared histogram to record top-level latency into (thread-safe)
     * @param deterministic      if {@code true}, each downstream call sleeps for the sampled
     *                           duration so wall-clock time reflects the simulation; if
     *                           {@code false}, latency is recorded instantly
     */
    public TailAmplificationScenario(LatencyInjector downstreamInjector,
                                     LatencyHistogram histogram,
                                     boolean deterministic) {
        this.downstreamInjector = downstreamInjector;
        this.histogram = histogram;
        this.deterministic = deterministic;
    }

    /**
     * Runs the tail-amplification experiment and returns one {@link PercentileSnapshot}
     * per snapshot interval.
     *
     * <p>Each top-level request fans out to {@value #FAN_OUT_DEGREE} downstream
     * services concurrently via {@link ScopedRunner#fanOut}. The recorded latency for
     * that top-level request is the maximum of the downstream response times, making
     * tail amplification visible in the p99 and p99.9 percentiles.
     *
     * @param args parsed CLI arguments controlling duration, concurrency, and snapshot interval
     * @return ordered list of percentile snapshots, one per completed snapshot interval
     */
    public List<PercentileSnapshot> run(CliArgs args) {
        long startMs = System.currentTimeMillis();
        long durationMs = args.duration().toMillis();
        long snapshotIntervalMs = args.snapshotInterval().toMillis();
        long nextSnapshotMs = startMs + snapshotIntervalMs;
        long totalRequests = 0;
        long totalErrors = 0;
        List<PercentileSnapshot> snapshots = new ArrayList<>();

        while (System.currentTimeMillis() - startMs < durationMs) {
            int concurrency = args.concurrency();
            List<Callable<Long>> tasks = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> {
                    // Fan out to FAN_OUT_DEGREE downstream services
                    List<Callable<Long>> downstream = new ArrayList<>(FAN_OUT_DEGREE);
                    for (int j = 0; j < FAN_OUT_DEGREE; j++) {
                        downstream.add(() -> {
                            long latencyMs = downstreamInjector.sampleLatencyMs();
                            if (deterministic) {
                                Thread.sleep(latencyMs);
                            }
                            return latencyMs;
                        });
                    }
                    // Top-level latency = max of all downstream (tail amplification)
                    List<Long> results = ScopedRunner.fanOut(downstream);
                    long maxLatency = results.stream().mapToLong(Long::longValue).max().orElse(0L);
                    histogram.recordLatency(maxLatency);
                    return maxLatency;
                });
            }

            try {
                ScopedRunner.fanOut(tasks);
                totalRequests += concurrency;
            } catch (RuntimeException e) {
                totalErrors++;
                totalRequests += concurrency;
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs >= nextSnapshotMs) {
                long elapsedSeconds = (nowMs - startMs) / 1000L;
                Histogram interval = histogram.intervalHistogram();
                long intervalRequests = interval.getTotalCount();
                double throughputRps = intervalRequests > 0
                        ? intervalRequests / (snapshotIntervalMs / 1000.0)
                        : 0.0;
                snapshots.add(PercentileSnapshot.from(interval, startMs, elapsedSeconds,
                        throughputRps, totalErrors, totalRequests));
                nextSnapshotMs += snapshotIntervalMs;
            }
        }

        return snapshots;
    }
}
