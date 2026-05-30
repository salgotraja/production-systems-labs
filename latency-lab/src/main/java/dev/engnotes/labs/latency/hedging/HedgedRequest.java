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

import dev.engnotes.labs.commons.concurrency.ScopedRunner;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one logical request with an optional duplicate attempt after a hedge delay.
 */
public final class HedgedRequest {

    private final boolean deterministic;

    public HedgedRequest(boolean deterministic) {
        this.deterministic = deterministic;
    }

    public HedgedRequestResult execute(long primaryLatencyMs, long secondaryLatencyMs, long hedgeDelayMs) {
        if (primaryLatencyMs <= hedgeDelayMs) {
            sleepIfLive(primaryLatencyMs);
            return new HedgedRequestResult(primaryLatencyMs, false, 1, 0, 0, false);
        }

        if (deterministic) {
            return deterministicResult(primaryLatencyMs, secondaryLatencyMs, hedgeDelayMs);
        }

        AtomicBoolean wastedCompletion = new AtomicBoolean(false);
        Callable<Long> primary = () -> {
            Thread.sleep(primaryLatencyMs);
            return primaryLatencyMs;
        };
        Callable<Long> secondary = () -> {
            Thread.sleep(hedgeDelayMs);
            Thread.sleep(secondaryLatencyMs);
            return hedgeDelayMs + secondaryLatencyMs;
        };

        long latencyMs = ScopedRunner.hedge(primary, secondary, () -> wastedCompletion.set(true));
        long wastedWorkMs = wastedWork(primaryLatencyMs, secondaryLatencyMs, hedgeDelayMs);
        return new HedgedRequestResult(latencyMs, true, 2, 1, wastedWorkMs, wastedCompletion.get());
    }

    static HedgedRequestResult deterministicResult(
            long primaryLatencyMs,
            long secondaryLatencyMs,
            long hedgeDelayMs) {

        long secondaryCompletionMs = hedgeDelayMs + secondaryLatencyMs;
        long latencyMs = Math.min(primaryLatencyMs, secondaryCompletionMs);
        long wastedWorkMs = wastedWork(primaryLatencyMs, secondaryLatencyMs, hedgeDelayMs);
        boolean wastedCompletion = primaryLatencyMs == secondaryCompletionMs;
        return new HedgedRequestResult(latencyMs, true, 2, 1, wastedWorkMs, wastedCompletion);
    }

    private void sleepIfLive(long latencyMs) {
        if (!deterministic) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("request interrupted", e);
            }
        }
    }

    private static long wastedWork(long primaryLatencyMs, long secondaryLatencyMs, long hedgeDelayMs) {
        long secondaryCompletionMs = hedgeDelayMs + secondaryLatencyMs;
        if (primaryLatencyMs <= secondaryCompletionMs) {
            return Math.max(0L, primaryLatencyMs - hedgeDelayMs);
        }
        return secondaryCompletionMs;
    }
}
