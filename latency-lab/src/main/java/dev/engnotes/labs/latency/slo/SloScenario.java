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
package dev.engnotes.labs.latency.slo;

import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;

public final class SloScenario {

    private static final int[] OVERLOAD_MS_BY_SECOND = {0, 40, 260, 460, 80};

    public SloRunResult run(SloTarget target, long durationSeconds, int concurrency) {
        long seconds = Math.max(1L, Math.min(durationSeconds, OVERLOAD_MS_BY_SECOND.length));
        long requestsPerSecond = Math.max(1L, concurrency * 100L);
        List<SloSummary> summaries = new ArrayList<>();
        List<SloWindow> windows = new ArrayList<>();

        for (ArchitecturePattern pattern : ArchitecturePattern.values()) {
            PatternResult patternResult = runPattern(pattern, target, seconds, requestsPerSecond);
            summaries.add(patternResult.summary());
            windows.addAll(patternResult.windows());
        }

        return new SloRunResult(target, summaries, windows);
    }

    private static PatternResult runPattern(
            ArchitecturePattern pattern,
            SloTarget target,
            long seconds,
            long requestsPerSecond) {

        List<SloWindow> windows = new ArrayList<>(Math.toIntExact(seconds));
        long cumulativeTotal = 0L;
        long cumulativeBad = 0L;
        long totalGood = 0L;
        long totalBad = 0L;
        double worstBurnRate = 0.0;
        boolean alertTriggered = false;

        for (int second = 1; second <= seconds; second++) {
            WindowStats stats = simulateSecond(pattern, second, requestsPerSecond, target);
            double burnRate = BurnRateCalculator.burnRate(stats.badRequests(), stats.totalRequests(), target);
            cumulativeTotal += stats.totalRequests();
            cumulativeBad += stats.badRequests();
            totalGood += stats.goodRequests();
            totalBad += stats.badRequests();
            worstBurnRate = Math.max(worstBurnRate, burnRate);
            boolean alerting = burnRate >= 2.0 || stats.p99Ms() > target.latencyThresholdMs();
            alertTriggered = alertTriggered || alerting;
            double consumedBudgetPct = cumulativeTotal == 0L
                    ? 0.0
                    : (cumulativeBad * 100.0) / Math.max(1.0, cumulativeTotal * target.errorBudgetRatio());
            windows.add(new SloWindow(
                    pattern,
                    second,
                    stats.totalRequests(),
                    stats.goodRequests(),
                    stats.badRequests(),
                    stats.p99Ms(),
                    stats.badEventPct(),
                    burnRate,
                    Math.max(0.0, 100.0 - consumedBudgetPct),
                    alerting));
        }

        long totalRequests = totalGood + totalBad;
        double achievedSliPct = totalRequests == 0L ? 100.0 : (totalGood * 100.0) / totalRequests;
        double finalBudgetConsumedPct = totalRequests == 0L
                ? 0.0
                : (totalBad * 100.0) / Math.max(1.0, totalRequests * target.errorBudgetRatio());
        SloSummary summary = new SloSummary(
                pattern,
                totalRequests,
                totalGood,
                totalBad,
                achievedSliPct,
                worstBurnRate,
                Math.max(0.0, 100.0 - finalBudgetConsumedPct),
                alertTriggered);
        return new PatternResult(summary, windows);
    }

    private static WindowStats simulateSecond(
            ArchitecturePattern pattern,
            int second,
            long requests,
            SloTarget target) {

        Histogram histogram = new Histogram(60_000L, 3);
        long good = 0L;
        long bad = 0L;

        for (long request = 0L; request < requests; request++) {
            RequestOutcome outcome = outcome(pattern, second, request);
            histogram.recordValue(outcome.latencyMs());
            if (outcome.success() && outcome.latencyMs() <= target.latencyThresholdMs()) {
                good++;
            } else {
                bad++;
            }
        }

        double p99 = histogram.getValueAtPercentile(99.0);
        double badPct = requests == 0L ? 0.0 : (bad * 100.0) / requests;
        return new WindowStats(requests, good, bad, p99, badPct);
    }

    private static RequestOutcome outcome(ArchitecturePattern pattern, int second, long request) {
        int overloadMs = OVERLOAD_MS_BY_SECOND[second - 1];
        long baseLatencyMs = 75L + ((request * 17L) % 45L);
        boolean expensiveRequest = request % 100L >= 92L;

        return switch (pattern) {
            case BASELINE -> {
                long latencyMs = baseLatencyMs + overloadMs + (expensiveRequest ? 70L : 0L);
                yield new RequestOutcome(true, latencyMs);
            }
            case BULKHEAD -> {
                boolean isolatedFailure = overloadMs >= 260 && request % 100L >= 99L;
                long cappedOverloadMs = Math.min(overloadMs, 30L);
                long latencyMs = baseLatencyMs + cappedOverloadMs + (expensiveRequest ? 40L : 0L);
                yield new RequestOutcome(!isolatedFailure, latencyMs);
            }
            case TIMEOUT_BUDGET -> {
                long rawLatencyMs = baseLatencyMs + overloadMs + (expensiveRequest ? 70L : 0L);
                if (rawLatencyMs > 200L) {
                    yield new RequestOutcome(false, 200L);
                }
                yield new RequestOutcome(true, rawLatencyMs);
            }
            case CONCURRENCY_LIMIT -> {
                boolean rejected = overloadMs >= 260 && request % 100L >= 98L;
                long cappedOverloadMs = Math.min(overloadMs, 30L);
                long latencyMs = rejected ? 5L : baseLatencyMs + cappedOverloadMs + (expensiveRequest ? 30L : 0L);
                yield new RequestOutcome(!rejected, latencyMs);
            }
        };
    }

    private record RequestOutcome(boolean success, long latencyMs) {
    }

    private record WindowStats(long totalRequests, long goodRequests, long badRequests, double p99Ms, double badEventPct) {
    }

    private record PatternResult(SloSummary summary, List<SloWindow> windows) {
    }
}
