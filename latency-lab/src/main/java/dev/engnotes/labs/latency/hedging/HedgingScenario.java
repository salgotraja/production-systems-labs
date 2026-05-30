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
package dev.engnotes.labs.latency.hedging;

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import dev.engnotes.labs.latency.simulation.LatencyInjector;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class HedgingScenario {

    private static final long PRIMARY_SEED = 42L;
    private static final long SECONDARY_SEED = 4242L;

    private final boolean deterministic;

    public HedgingScenario(boolean deterministic) {
        this.deterministic = deterministic;
    }

    public HedgingScenarioResult run(CliArgs args, HedgeThreshold selectedThreshold) {
        int seconds = Math.toIntExact(Math.max(1L, args.duration().toSeconds()));
        int snapshotIntervalSeconds = Math.min(seconds,
                Math.toIntExact(Math.max(1L, args.snapshotInterval().toSeconds())));
        int requestsPerSecond = Math.max(1, args.concurrency() * 100);
        int totalRequests = seconds * requestsPerSecond;

        List<Long> primaryLatencies = sampleLatencies(totalRequests, new LatencyInjector(deterministic, PRIMARY_SEED));
        List<Long> secondaryLatencies = sampleLatencies(totalRequests, new LatencyInjector(deterministic, SECONDARY_SEED));

        HedgingRunResult baseline = runBaseline(primaryLatencies, seconds, snapshotIntervalSeconds, requestsPerSecond);
        Map<HedgeThreshold, Long> thresholdValues = thresholds(primaryLatencies);
        Map<HedgeThreshold, HedgingRunResult> hedgedRuns = new EnumMap<>(HedgeThreshold.class);
        List<HedgeCostPoint> costPoints = new ArrayList<>();

        for (HedgeThreshold threshold : HedgeThreshold.values()) {
            long thresholdMs = thresholdValues.get(threshold);
            HedgingRunResult hedged = runHedged(primaryLatencies, secondaryLatencies,
                    seconds, snapshotIntervalSeconds, requestsPerSecond, thresholdMs);
            hedgedRuns.put(threshold, hedged);
            costPoints.add(costPoint(threshold, thresholdMs, baseline, hedged));
        }

        return new HedgingScenarioResult(baseline, hedgedRuns.get(selectedThreshold), costPoints, selectedThreshold);
    }

    private static List<Long> sampleLatencies(int count, LatencyInjector injector) {
        List<Long> latencies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            latencies.add(injector.sampleLatencyMs());
        }
        return latencies;
    }

    private HedgingRunResult runBaseline(
            List<Long> latencies,
            int seconds,
            int snapshotIntervalSeconds,
            int requestsPerSecond) {

        List<PercentileSnapshot> snapshots = new ArrayList<>(seconds);
        Histogram wholeRun = new Histogram(60_000L, 3);
        long startMs = deterministic ? 0L : System.currentTimeMillis();
        long totalRequests = 0L;

        for (int elapsedSeconds = snapshotIntervalSeconds; elapsedSeconds <= seconds;
                elapsedSeconds += snapshotIntervalSeconds) {
            Histogram interval = new Histogram(60_000L, 3);
            int start = (elapsedSeconds - snapshotIntervalSeconds) * requestsPerSecond;
            int end = elapsedSeconds * requestsPerSecond;
            for (int i = start; i < end; i++) {
                long latencyMs = latencies.get(i);
                interval.recordValue(latencyMs);
                wholeRun.recordValue(latencyMs);
            }
            totalRequests += interval.getTotalCount();
            snapshots.add(PercentileSnapshot.from(interval, startMs, elapsedSeconds,
                    interval.getTotalCount() / (double) snapshotIntervalSeconds, 0L, totalRequests));
        }

        return new HedgingRunResult(snapshots, totalRequests, totalRequests,
                0L, 0L, 0L, wholeRun.getValueAtPercentile(99.0));
    }

    private HedgingRunResult runHedged(
            List<Long> primaryLatencies,
            List<Long> secondaryLatencies,
            int seconds,
            int snapshotIntervalSeconds,
            int requestsPerSecond,
            long thresholdMs) {

        List<PercentileSnapshot> snapshots = new ArrayList<>(seconds);
        Histogram wholeRun = new Histogram(60_000L, 3);
        HedgedRequest hedgedRequest = new HedgedRequest(true);
        long startMs = deterministic ? 0L : System.currentTimeMillis();
        long totalRequests = 0L;
        long totalAttempts = 0L;
        long hedgeRequests = 0L;
        long wastedWorkMs = 0L;
        long wastedCompletions = 0L;

        for (int elapsedSeconds = snapshotIntervalSeconds; elapsedSeconds <= seconds;
                elapsedSeconds += snapshotIntervalSeconds) {
            Histogram interval = new Histogram(60_000L, 3);
            int start = (elapsedSeconds - snapshotIntervalSeconds) * requestsPerSecond;
            int end = elapsedSeconds * requestsPerSecond;
            for (int i = start; i < end; i++) {
                HedgedRequestResult result = hedgedRequest.execute(
                        primaryLatencies.get(i),
                        secondaryLatencies.get(i),
                        thresholdMs);
                interval.recordValue(result.latencyMs());
                wholeRun.recordValue(result.latencyMs());
                totalAttempts += result.attempts();
                if (result.hedgeLaunched()) {
                    hedgeRequests++;
                    wastedWorkMs += result.wastedWorkMs();
                    if (result.wastedCompletion()) {
                        wastedCompletions++;
                    }
                }
            }
            totalRequests += interval.getTotalCount();
            snapshots.add(PercentileSnapshot.from(interval, startMs, elapsedSeconds,
                    interval.getTotalCount() / (double) snapshotIntervalSeconds, 0L, totalRequests));
        }

        return new HedgingRunResult(snapshots, totalRequests, totalAttempts,
                hedgeRequests, wastedWorkMs, wastedCompletions, wholeRun.getValueAtPercentile(99.0));
    }

    private static Map<HedgeThreshold, Long> thresholds(List<Long> latencies) {
        Histogram histogram = new Histogram(60_000L, 3);
        for (long latencyMs : latencies) {
            histogram.recordValue(latencyMs);
        }
        Map<HedgeThreshold, Long> values = new EnumMap<>(HedgeThreshold.class);
        for (HedgeThreshold threshold : HedgeThreshold.values()) {
            values.put(threshold, histogram.getValueAtPercentile(threshold.percentile()));
        }
        return values;
    }

    private static HedgeCostPoint costPoint(
            HedgeThreshold threshold,
            long thresholdMs,
            HedgingRunResult baseline,
            HedgingRunResult hedged) {

        double improvementPct = baseline.p99Ms() == 0.0
                ? 0.0
                : ((baseline.p99Ms() - hedged.p99Ms()) / baseline.p99Ms()) * 100.0;
        return new HedgeCostPoint(
                threshold,
                thresholdMs,
                baseline.p99Ms(),
                hedged.p99Ms(),
                improvementPct,
                hedged.extraLoadPct(),
                hedged.hedgeRequests(),
                hedged.totalRequests(),
                hedged.wastedWorkMs(),
                hedged.wastedCompletions());
    }
}
