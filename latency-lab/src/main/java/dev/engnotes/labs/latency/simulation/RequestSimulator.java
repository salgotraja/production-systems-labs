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
 * Runs concurrent virtual clients for the experiment duration, recording sampled
 * latency values into the shared {@link LatencyHistogram} and emitting periodic
 * {@link PercentileSnapshot}s.
 *
 * <p>Each batch of {@link CliArgs#concurrency()} tasks is forked via
 * {@link ScopedRunner#fanOut}, so every batch runs in parallel virtual threads.
 * Batches are dispatched sequentially until the experiment duration elapses.
 */
public final class RequestSimulator {

    private final LatencyInjector injector;
    private final LatencyHistogram histogram;
    private final boolean deterministic;

    /**
     * Creates a new {@code RequestSimulator}.
     *
     * @param injector      provides sampled latency values per request
     * @param histogram     shared histogram to record latency into (thread-safe)
     * @param deterministic if {@code true}, each simulated request calls
     *                      {@code Thread.sleep} for the sampled latency so elapsed
     *                      wall-clock time matches the simulation; if {@code false},
     *                      latency is recorded instantly for faster throughput
     */
    public RequestSimulator(LatencyInjector injector, LatencyHistogram histogram, boolean deterministic) {
        this.injector = injector;
        this.histogram = histogram;
        this.deterministic = deterministic;
    }

    /**
     * Runs the experiment and returns one {@link PercentileSnapshot} per snapshot interval.
     *
     * <p>The experiment runs until {@link CliArgs#duration()} has elapsed, dispatching
     * batches of {@link CliArgs#concurrency()} concurrent requests. A snapshot is
     * appended to the result list each time {@link CliArgs#snapshotInterval()} elapses.
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
                    long latencyMs = injector.sampleLatencyMs();
                    if (deterministic) {
                        Thread.sleep(latencyMs);
                    }
                    histogram.recordLatency(latencyMs);
                    return latencyMs;
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
