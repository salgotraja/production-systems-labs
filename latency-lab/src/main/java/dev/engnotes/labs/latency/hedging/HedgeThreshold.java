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

import java.util.Arrays;
import java.util.Locale;

public enum HedgeThreshold {
    P90("p90", 90.0),
    P95("p95", 95.0),
    P99("p99", 99.0);

    private final String label;
    private final double percentile;

    HedgeThreshold(String label, double percentile) {
        this.label = label;
        this.percentile = percentile;
    }

    public String label() {
        return label;
    }

    public double percentile() {
        return percentile;
    }

    public static HedgeThreshold parse(String value) {
        if (value == null || value.isBlank()) {
            return P95;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(threshold -> threshold.label.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported hedge threshold '" + value + "'. Expected one of: p90, p95, p99"));
    }
}
