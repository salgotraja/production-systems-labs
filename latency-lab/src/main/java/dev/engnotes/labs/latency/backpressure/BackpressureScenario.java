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
package dev.engnotes.labs.latency.backpressure;

import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;

public final class BackpressureScenario {

    private static final long ADMITTED_RPS = 100L;
    private static final long SERVICE_TIME_MS = 10L;

    public BackpressureRunResult run(long durationSeconds, int concurrency, List<BackpressureStrategy> strategies) {
        long seconds = Math.max(1L, durationSeconds);
        long incomingRps = Math.max(1L, concurrency * 20L);
        long totalIncoming = incomingRps * seconds;
        long arrivalIntervalMs = Math.max(1L, 1_000L / incomingRps);

        List<BackpressureSummary> summaries = new ArrayList<>(strategies.size());
        List<BackpressureSnapshot> snapshots = new ArrayList<>();

        for (BackpressureStrategy strategy : strategies) {
            BackpressureController controller = controller(strategy);
            StrategyRun run = runStrategy(controller, totalIncoming, incomingRps, arrivalIntervalMs, seconds);
            summaries.add(run.summary());
            snapshots.addAll(run.snapshots());
        }

        return new BackpressureRunResult(summaries, snapshots, incomingRps, ADMITTED_RPS, seconds);
    }

    private static BackpressureController controller(BackpressureStrategy strategy) {
        return switch (strategy) {
            case TOKEN_BUCKET -> new TokenBucket(ADMITTED_RPS, ADMITTED_RPS, SERVICE_TIME_MS);
            case BOUNDED_QUEUE -> new BoundedQueue(50, SERVICE_TIME_MS);
            case LEAKY_BUCKET -> new LeakyBucket(25, ADMITTED_RPS);
            case LOAD_SHEDDER -> new LoadShedder(5, 50L);
            case RESILIENCE4J_RATE_LIMITER -> new Resilience4jRateLimiterController((int) ADMITTED_RPS, SERVICE_TIME_MS);
        };
    }

    private static StrategyRun runStrategy(
            BackpressureController controller,
            long totalIncoming,
            long incomingRps,
            long arrivalIntervalMs,
            long durationSeconds) {

        Histogram wholeRun = new Histogram(60_000L, 3);
        Histogram interval = new Histogram(60_000L, 3);
        List<BackpressureSnapshot> snapshots = new ArrayList<>(Math.toIntExact(durationSeconds));
        long totalAccepted = 0L;
        long totalRejected = 0L;
        long intervalAccepted = 0L;
        long intervalRejected = 0L;
        int maxBuffered = 0;
        int intervalMaxBuffered = 0;

        for (long request = 0L; request < totalIncoming; request++) {
            long arrivalMs = request * arrivalIntervalMs;
            long elapsedSecond = Math.min(durationSeconds, (arrivalMs / 1_000L) + 1L);
            BackpressureDecision decision = controller.admit(arrivalMs);
            maxBuffered = Math.max(maxBuffered, controller.buffered());
            intervalMaxBuffered = Math.max(intervalMaxBuffered, controller.buffered());

            if (decision.accepted()) {
                totalAccepted++;
                intervalAccepted++;
                wholeRun.recordValue(decision.latencyMs());
                interval.recordValue(decision.latencyMs());
            } else {
                totalRejected++;
                intervalRejected++;
            }

            if ((request + 1L) % incomingRps == 0L) {
                snapshots.add(new BackpressureSnapshot(
                        controller.strategy(),
                        elapsedSecond,
                        intervalAccepted,
                        intervalRejected,
                        intervalAccepted,
                        interval.getTotalCount() == 0 ? 0.0 : interval.getValueAtPercentile(50.0),
                        interval.getTotalCount() == 0 ? 0.0 : interval.getValueAtPercentile(99.0),
                        intervalMaxBuffered));
                interval = new Histogram(60_000L, 3);
                intervalAccepted = 0L;
                intervalRejected = 0L;
                intervalMaxBuffered = 0;
            }
        }

        double acceptancePct = totalIncoming == 0 ? 0.0 : (totalAccepted * 100.0) / totalIncoming;
        BackpressureSummary summary = new BackpressureSummary(
                controller.strategy(),
                totalIncoming,
                totalAccepted,
                totalRejected,
                acceptancePct,
                wholeRun.getTotalCount() == 0 ? 0.0 : wholeRun.getValueAtPercentile(50.0),
                wholeRun.getTotalCount() == 0 ? 0.0 : wholeRun.getValueAtPercentile(99.0),
                maxBuffered);
        return new StrategyRun(summary, snapshots);
    }

    private record StrategyRun(BackpressureSummary summary, List<BackpressureSnapshot> snapshots) {
    }
}
