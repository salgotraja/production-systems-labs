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
package dev.engnotes.labs.commons.terminal;

import dev.engnotes.labs.commons.histogram.PercentileSnapshot;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Renders live experiment progress to stdout.
 * <p>
 * Two modes are selected automatically:
 * <ul>
 *   <li><b>TTY mode</b> - ANSI escape codes keep a live percentile table updating in-place.
 *       Detected when stdout is a real terminal ({@code System.console() != null}).</li>
 *   <li><b>Streaming mode</b> - One line per snapshot, no escape codes. Used in CI and
 *       when stdout is piped or redirected.</li>
 * </ul>
 * Not thread-safe. Call {@link #render(PercentileSnapshot, long, long)} from a single
 * reporter thread.
 */
public final class TerminalRenderer {

    // ANSI escape sequences
    private static final String ANSI_CLEAR_LINE = "\r\033[K";
    private static final String ANSI_MOVE_UP    = "\033[%dA";
    private static final String ANSI_BOLD       = "\033[1m";
    private static final String ANSI_RESET      = "\033[0m";
    private static final String ANSI_CYAN       = "\033[36m";
    private static final String ANSI_GREEN      = "\033[32m";

    // Number of rows the live table occupies (header + data + blank line)
    private static final int TABLE_ROWS = 4;

    private final PrintStream out;
    private final boolean ttyMode;
    private boolean tableDrawn = false;

    private TerminalRenderer(PrintStream out, boolean ttyMode) {
        this.out = out;
        this.ttyMode = ttyMode;
    }

    /** Creates a renderer that auto-detects TTY vs streaming mode. */
    public static TerminalRenderer create() {
        boolean isTty = System.console() != null;
        return new TerminalRenderer(System.out, isTty);
    }

    /** Creates a renderer in streaming mode, writing to the given stream. Useful for tests. */
    public static TerminalRenderer streaming(PrintStream out) {
        return new TerminalRenderer(out, false);
    }

    /**
     * Renders one snapshot.
     *
     * @param snap          the latest percentile snapshot
     * @param durationSecs  total experiment duration in seconds (for progress calculation)
     * @param remainingSecs seconds remaining in the experiment
     */
    public void render(PercentileSnapshot snap, long durationSecs, long remainingSecs) {
        if (ttyMode) {
            renderAnsi(snap, durationSecs, remainingSecs);
        } else {
            renderStreaming(snap, remainingSecs);
        }
    }

    /** Clears the live table if in TTY mode (call at experiment end). */
    public void finish(String message) {
        if (ttyMode && tableDrawn) {
            out.printf(ANSI_MOVE_UP, TABLE_ROWS);
            for (int i = 0; i < TABLE_ROWS; i++) {
                out.print(ANSI_CLEAR_LINE + "\n");
            }
            out.printf(ANSI_MOVE_UP, TABLE_ROWS);
        }
        out.println(message);
    }

    private void renderAnsi(PercentileSnapshot snap, long durationSecs, long remainingSecs) {
        if (tableDrawn) {
            // Move cursor up to overwrite previous table
            out.printf(ANSI_MOVE_UP, TABLE_ROWS);
        }

        int progressPct = durationSecs > 0
                ? (int) (100L * (durationSecs - remainingSecs) / durationSecs)
                : 100;
        String progressBar = buildProgressBar(progressPct, 20);

        out.printf(ANSI_CLEAR_LINE + ANSI_BOLD + ANSI_CYAN
                + "  %-12s  %-10s  %-10s  %-10s  %-10s  %-12s%n" + ANSI_RESET,
                "elapsed", "p50", "p95", "p99", "p99.9", "throughput");

        out.printf(ANSI_CLEAR_LINE + ANSI_GREEN
                + "  %-12s  %-10s  %-10s  %-10s  %-10s  %-12s%n" + ANSI_RESET,
                snap.elapsedSeconds() + "s",
                formatMs(snap.p50Ms()),
                formatMs(snap.p95Ms()),
                formatMs(snap.p99Ms()),
                formatMs(snap.p999Ms()),
                formatRps(snap.throughputRps()) + " rps");

        out.printf(ANSI_CLEAR_LINE + "  Progress: [%s] %d%%%n", progressBar, progressPct);

        out.print(ANSI_CLEAR_LINE + "\n");

        tableDrawn = true;
    }

    private void renderStreaming(PercentileSnapshot snap, long remainingSecs) {
        out.printf("  t=%4ds | p50=%s p95=%s p99=%s p99.9=%s | tput=%s | remaining=%ds%n",
                snap.elapsedSeconds(),
                formatMs(snap.p50Ms()),
                formatMs(snap.p95Ms()),
                formatMs(snap.p99Ms()),
                formatMs(snap.p999Ms()),
                formatRps(snap.throughputRps()),
                remainingSecs);
    }

    private static String buildProgressBar(int pct, int width) {
        int filled = (int) ((pct / 100.0) * width);
        return "=".repeat(filled) + " ".repeat(width - filled);
    }

    private static String formatMs(double ms) {
        return String.format(Locale.ROOT, "%6.1fms", ms);
    }

    private static String formatRps(double rps) {
        return String.format(Locale.ROOT, "%7.0f", rps);
    }
}
