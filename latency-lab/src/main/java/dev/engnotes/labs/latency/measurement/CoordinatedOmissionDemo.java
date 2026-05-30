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
package dev.engnotes.labs.latency.measurement;

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;

public final class CoordinatedOmissionDemo {

    private static final long BASE_LATENCY_MS = 10L;
    private static final long PAUSE_DURATION_MS = 500L;

    public CoordinatedOmissionResult run(CliArgs args, MeasurementMode mode) {
        long durationMs = Math.max(1_000L, args.duration().toMillis());
        long snapshotIntervalMs = Math.max(1_000L, args.snapshotInterval().toMillis());
        snapshotIntervalMs = Math.min(snapshotIntervalMs, durationMs);
        int rateRps = Math.max(1, args.concurrency() * 10);
        long expectedIntervalMs = Math.max(1L, 1_000L / rateRps);
        PauseServiceModel model = new PauseServiceModel(
                BASE_LATENCY_MS,
                durationMs / 2L,
                Math.min(PAUSE_DURATION_MS, Math.max(1L, durationMs / 4L)));

        CoordinatedOmissionRun closedRaw = mode.includesClosedLoop()
                ? runClosedLoopRaw(durationMs, snapshotIntervalMs, model)
                : CoordinatedOmissionRun.empty();
        CoordinatedOmissionRun closedCorrected = mode.includesClosedLoop()
                ? runClosedLoopCorrected(durationMs, snapshotIntervalMs, expectedIntervalMs, model)
                : CoordinatedOmissionRun.empty();
        CoordinatedOmissionRun openLoop = mode.includesOpenLoop()
                ? runOpenLoop(durationMs, snapshotIntervalMs, expectedIntervalMs, model)
                : CoordinatedOmissionRun.empty();

        return new CoordinatedOmissionResult(
                closedRaw,
                closedCorrected,
                openLoop,
                expectedIntervalMs,
                model);
    }

    private static CoordinatedOmissionRun runClosedLoopRaw(
            long durationMs,
            long snapshotIntervalMs,
            PauseServiceModel model) {

        List<Histogram> intervals = intervalHistograms(durationMs, snapshotIntervalMs);
        Histogram wholeRun = new Histogram(60_000L, 3);
        long nowMs = 0L;
        long totalRequests = 0L;

        while (nowMs < durationMs) {
            long latencyMs = model.latencyForArrival(nowMs);
            int intervalIndex = intervalIndex(nowMs, snapshotIntervalMs, intervals.size());
            intervals.get(intervalIndex).recordValue(latencyMs);
            wholeRun.recordValue(latencyMs);
            totalRequests++;
            nowMs += latencyMs;
        }

        return new CoordinatedOmissionRun(
                snapshots(intervals, snapshotIntervalMs),
                totalRequests,
                wholeRun.getValueAtPercentile(99.0));
    }

    private static CoordinatedOmissionRun runClosedLoopCorrected(
            long durationMs,
            long snapshotIntervalMs,
            long expectedIntervalMs,
            PauseServiceModel model) {

        List<Histogram> rawIntervals = intervalHistograms(durationMs, snapshotIntervalMs);
        List<Histogram> correctedIntervals = intervalHistograms(durationMs, snapshotIntervalMs);
        Histogram wholeRun = new Histogram(60_000L, 3);
        long nowMs = 0L;
        long totalRequests = 0L;

        while (nowMs < durationMs) {
            long latencyMs = model.latencyForArrival(nowMs);
            int intervalIndex = intervalIndex(nowMs, snapshotIntervalMs, rawIntervals.size());
            rawIntervals.get(intervalIndex).recordValue(latencyMs);
            wholeRun.recordValue(latencyMs);
            totalRequests++;
            nowMs += latencyMs;
        }

        Histogram correctedWholeRun = new Histogram(60_000L, 3);
        for (int i = 0; i < rawIntervals.size(); i++) {
            Histogram corrected = rawIntervals.get(i).copyCorrectedForCoordinatedOmission(expectedIntervalMs);
            correctedIntervals.set(i, corrected);
            correctedWholeRun.add(corrected);
        }

        return new CoordinatedOmissionRun(
                snapshots(correctedIntervals, snapshotIntervalMs),
                totalRequests,
                correctedWholeRun.getValueAtPercentile(99.0));
    }

    private static CoordinatedOmissionRun runOpenLoop(
            long durationMs,
            long snapshotIntervalMs,
            long expectedIntervalMs,
            PauseServiceModel model) {

        List<Histogram> intervals = intervalHistograms(durationMs, snapshotIntervalMs);
        Histogram wholeRun = new Histogram(60_000L, 3);
        long totalRequests = 0L;

        for (long arrivalMs = 0L; arrivalMs < durationMs; arrivalMs += expectedIntervalMs) {
            long latencyMs = model.latencyForArrival(arrivalMs);
            int intervalIndex = intervalIndex(arrivalMs, snapshotIntervalMs, intervals.size());
            intervals.get(intervalIndex).recordValue(latencyMs);
            wholeRun.recordValue(latencyMs);
            totalRequests++;
        }

        return new CoordinatedOmissionRun(
                snapshots(intervals, snapshotIntervalMs),
                totalRequests,
                wholeRun.getValueAtPercentile(99.0));
    }

    private static List<Histogram> intervalHistograms(long durationMs, long snapshotIntervalMs) {
        int count = Math.toIntExact(Math.max(1L, durationMs / snapshotIntervalMs));
        List<Histogram> intervals = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            intervals.add(new Histogram(60_000L, 3));
        }
        return intervals;
    }

    private static List<PercentileSnapshot> snapshots(List<Histogram> intervals, long snapshotIntervalMs) {
        List<PercentileSnapshot> snapshots = new ArrayList<>(intervals.size());
        long totalRequests = 0L;
        double intervalSeconds = snapshotIntervalMs / 1_000.0;
        for (int i = 0; i < intervals.size(); i++) {
            Histogram interval = intervals.get(i);
            totalRequests += interval.getTotalCount();
            long elapsedSeconds = ((i + 1L) * snapshotIntervalMs) / 1_000L;
            snapshots.add(PercentileSnapshot.from(interval, 0L, elapsedSeconds,
                    interval.getTotalCount() / intervalSeconds, 0L, totalRequests));
        }
        return snapshots;
    }

    private static int intervalIndex(long timestampMs, long snapshotIntervalMs, int intervalCount) {
        long index = timestampMs / snapshotIntervalMs;
        return Math.toIntExact(Math.min(index, intervalCount - 1L));
    }
}
