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
package dev.engnotes.labs.commons.csv;

import dev.engnotes.labs.commons.histogram.PercentileSnapshot;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Writes periodic CSV snapshots to a file.
 * <p>
 * Column schema (append-only - never reorder or remove columns per ADR-005):
 * <pre>
 * timestamp_ms,elapsed_s,p50_ms,p95_ms,p99_ms,p999_ms,throughput_rps,error_count,total_requests
 * </pre>
 * Thread-safety: not thread-safe. Call {@link #write(PercentileSnapshot)} from a single
 * reporter thread only.
 */
public final class CsvSnapshotWriter implements Closeable {

    public static final String HEADER =
            "timestamp_ms,elapsed_s,p50_ms,p95_ms,p99_ms,p999_ms,throughput_rps,error_count,total_requests";

    private final BufferedWriter writer;

    private CsvSnapshotWriter(BufferedWriter writer) throws IOException {
        this.writer = writer;
        writer.write(HEADER);
        writer.newLine();
        writer.flush();
    }

    /**
     * Opens (or creates) a CSV file at {@code path} and writes the header row.
     * Parent directories are created if they do not exist.
     *
     * @param path destination file path
     * @return a new {@code CsvSnapshotWriter}; caller must close it when done
     * @throws UncheckedIOException if the file cannot be opened or the header cannot be written
     */
    public static CsvSnapshotWriter open(Path path) {
        try {
            Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
            BufferedWriter w = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return new CsvSnapshotWriter(w);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open CSV file: " + path, e);
        }
    }

    /**
     * Appends one data row for the given snapshot.
     *
     * @throws UncheckedIOException if the row cannot be written
     */
    public void write(PercentileSnapshot snap) {
        try {
            writer.write(formatRow(snap));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV row", e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close CSV writer", e);
        }
    }

    public static String formatRow(PercentileSnapshot snap) {
        return snap.timestampMs()
                + "," + snap.elapsedSeconds()
                + "," + formatMs(snap.p50Ms())
                + "," + formatMs(snap.p95Ms())
                + "," + formatMs(snap.p99Ms())
                + "," + formatMs(snap.p999Ms())
                + "," + formatRps(snap.throughputRps())
                + "," + snap.errorCount()
                + "," + snap.totalRequests();
    }

    private static String formatMs(double ms) {
        // Locale.ROOT: comma-decimal locales (e.g. de_DE) would emit "20,0",
        // which both breaks the CSV delimiter and the golden-file contract.
        return String.format(Locale.ROOT, "%.1f", ms);
    }

    private static String formatRps(double rps) {
        return String.format(Locale.ROOT, "%.1f", rps);
    }
}
