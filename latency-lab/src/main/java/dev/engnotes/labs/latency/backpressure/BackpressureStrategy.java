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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum BackpressureStrategy {
    TOKEN_BUCKET("token-bucket"),
    BOUNDED_QUEUE("bounded-queue"),
    LEAKY_BUCKET("leaky-bucket"),
    LOAD_SHEDDER("load-shedder"),
    RESILIENCE4J_RATE_LIMITER("resilience4j-rate-limiter");

    private final String label;

    BackpressureStrategy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<BackpressureStrategy> parseSelection(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return List.of(values());
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(strategy -> strategy.label.equals(normalized))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported backpressure strategy '" + value
                        + "'. Expected: all, token-bucket, bounded-queue, leaky-bucket, "
                        + "load-shedder, resilience4j-rate-limiter"));
    }
}
