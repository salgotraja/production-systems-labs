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

/**
 * Parses {@code args[]} into a {@link CliArgs} instance.
 * <p>
 * Supports the stable CLI contract defined in ADR-005:
 * <pre>
 *   --deterministic            (boolean flag)
 *   --duration &lt;value&gt;         e.g. 30s, 2m, 1h
 *   --concurrency &lt;int&gt;
 *   --output-dir &lt;path&gt;
 *   --snapshot-interval &lt;value&gt;
 * </pre>
 * All unrecognized flags are stored as {@link CliArgs#extra(String) extras}
 * for post-specific consumption.
 */
public final class CliParser {

    private CliParser() {}

    public static CliArgs parse(String[] args) {
        CliArgs.Builder builder = CliArgs.builder();

        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            switch (token) {
                case "--deterministic" -> builder.deterministic(true);
                case "--duration" -> {
                    i = requireNext(args, i, token);
                    builder.duration(parseDuration(args[i]));
                }
                case "--concurrency" -> {
                    i = requireNext(args, i, token);
                    builder.concurrency(parseInt(args[i], token));
                }
                case "--output-dir" -> {
                    i = requireNext(args, i, token);
                    builder.outputDir(Path.of(args[i]));
                }
                case "--snapshot-interval" -> {
                    i = requireNext(args, i, token);
                    builder.snapshotInterval(parseDuration(args[i]));
                }
                default -> {
                    if (token.startsWith("--")) {
                        // Post-specific flag - store as extra
                        if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                            i++;
                            builder.extra(token, args[i]);
                        } else {
                            // Boolean-style flag with no value
                            builder.extra(token, "true");
                        }
                    } else {
                        throw new CliParseException("Unexpected argument: " + token);
                    }
                }
            }
        }

        return builder.build();
    }

    /**
     * Parses a duration string. Supported units: {@code s} (seconds), {@code m} (minutes),
     * {@code h} (hours). Example: {@code "30s"}, {@code "2m"}.
     */
    static Duration parseDuration(String value) {
        try {
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            } else if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            } else if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            } else if (value.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            } else {
                return Duration.ofSeconds(Long.parseLong(value));
            }
        } catch (NumberFormatException e) {
            throw new CliParseException("Invalid duration value: '" + value
                    + "'. Expected format: 30s, 2m, 1h, 500ms");
        }
    }

    private static int requireNext(String[] args, int i, String flag) {
        if (i + 1 >= args.length) {
            throw new CliParseException("Flag '" + flag + "' requires a value");
        }
        return i + 1;
    }

    private static int parseInt(String value, String flag) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CliParseException("Flag '" + flag + "' expects an integer, got: '" + value + "'");
        }
    }
}
