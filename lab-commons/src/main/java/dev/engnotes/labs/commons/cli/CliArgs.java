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
package dev.engnotes.labs.commons.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Parsed CLI arguments. All fields are immutable after construction.
 * <p>
 * Standard flags shared by every experiment:
 * <ul>
 *   <li>{@code --deterministic} — fixed seed + Thread.sleep delays for reproducibility</li>
 *   <li>{@code --duration}      — experiment run time (e.g. {@code 30s}, {@code 2m})</li>
 *   <li>{@code --concurrency}   — number of concurrent virtual clients</li>
 *   <li>{@code --output-dir}    — directory for CSV and PNG output</li>
 *   <li>{@code --snapshot-interval} — time between CSV snapshot rows</li>
 * </ul>
 * Post-specific flags are accessed via {@link #extra(String)}.
 */
public final class CliArgs {

    private final boolean deterministic;
    private final Duration duration;
    private final int concurrency;
    private final Path outputDir;
    private final Duration snapshotInterval;
    private final Map<String, String> extra;

    private CliArgs(
            boolean deterministic,
            Duration duration,
            int concurrency,
            Path outputDir,
            Duration snapshotInterval,
            Map<String, String> extra) {
        this.deterministic = deterministic;
        this.duration = duration;
        this.concurrency = concurrency;
        this.outputDir = outputDir;
        this.snapshotInterval = snapshotInterval;
        this.extra = Map.copyOf(extra);
    }

    public boolean isDeterministic() {
        return deterministic;
    }

    public Duration duration() {
        return duration;
    }

    public int concurrency() {
        return concurrency;
    }

    public Path outputDir() {
        return outputDir;
    }

    public Duration snapshotInterval() {
        return snapshotInterval;
    }

    /**
     * Returns the value for a post-specific flag, or {@code null} if not provided.
     * Flag names are normalized (leading {@code --} stripped, lowercase).
     */
    public String extra(String flagName) {
        return extra.get(normalize(flagName));
    }

    /** Returns {@code true} if a post-specific flag was provided (boolean-style flags). */
    public boolean hasExtra(String flagName) {
        return extra.containsKey(normalize(flagName));
    }

    private static String normalize(String flagName) {
        String s = flagName.toLowerCase();
        if (s.startsWith("--")) {
            s = s.substring(2);
        }
        return s;
    }

    @Override
    public String toString() {
        return "CliArgs{deterministic=" + deterministic
                + ", duration=" + duration
                + ", concurrency=" + concurrency
                + ", outputDir=" + outputDir
                + ", snapshotInterval=" + snapshotInterval
                + ", extra=" + extra + '}';
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean deterministic = false;
        private Duration duration = Duration.ofSeconds(30);
        private int concurrency = 100;
        private Path outputDir = Path.of("./results");
        private Duration snapshotInterval = Duration.ofSeconds(1);
        private final Map<String, String> extra = new HashMap<>();

        private Builder() {}

        public Builder deterministic(boolean value) {
            this.deterministic = value;
            return this;
        }

        public Builder duration(Duration value) {
            this.duration = value;
            return this;
        }

        public Builder concurrency(int value) {
            if (value < 1) throw new IllegalArgumentException("concurrency must be >= 1, got: " + value);
            this.concurrency = value;
            return this;
        }

        public Builder outputDir(Path value) {
            this.outputDir = value;
            return this;
        }

        public Builder snapshotInterval(Duration value) {
            this.snapshotInterval = value;
            return this;
        }

        public Builder extra(String key, String value) {
            this.extra.put(normalize(key), value);
            return this;
        }

        public CliArgs build() {
            return new CliArgs(deterministic, duration, concurrency, outputDir, snapshotInterval, extra);
        }

        private static String normalize(String key) {
            String s = key.toLowerCase();
            if (s.startsWith("--")) s = s.substring(2);
            return s;
        }
    }
}
