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

import java.util.Locale;

public record SloTarget(double objectivePct, long latencyThresholdMs) {

    private static final double DEFAULT_OBJECTIVE_PCT = 99.0;
    private static final long DEFAULT_LATENCY_THRESHOLD_MS = 200L;

    public SloTarget {
        if (objectivePct <= 0.0 || objectivePct >= 100.0) {
            throw new IllegalArgumentException("objectivePct must be between 0 and 100");
        }
        if (latencyThresholdMs < 1) {
            throw new IllegalArgumentException("latencyThresholdMs must be >= 1");
        }
    }

    public double errorBudgetPct() {
        return 100.0 - objectivePct;
    }

    public double errorBudgetRatio() {
        return errorBudgetPct() / 100.0;
    }

    public static SloTarget parse(String value) {
        if (value == null || value.isBlank()) {
            return new SloTarget(DEFAULT_OBJECTIVE_PCT, DEFAULT_LATENCY_THRESHOLD_MS);
        }

        String normalized = value.toLowerCase(Locale.ROOT).replace(" ", "");
        int marker = normalized.indexOf("p99<");
        if (marker < 0) {
            throw new IllegalArgumentException("Unsupported SLO target '" + value + "'. Expected format: p99<200ms");
        }

        String threshold = normalized.substring(marker + "p99<".length());
        if (!threshold.endsWith("ms")) {
            throw new IllegalArgumentException("Unsupported SLO target '" + value + "'. Threshold must use ms");
        }

        long thresholdMs = Long.parseLong(threshold.substring(0, threshold.length() - 2));
        return new SloTarget(DEFAULT_OBJECTIVE_PCT, thresholdMs);
    }
}
