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
package dev.engnotes.labs.commons;

import dev.engnotes.labs.commons.csv.CsvSnapshotWriter;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvSnapshotWriterTest {

    @Test
    void writerCreatesFileWithHeader(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.csv");
        try (CsvSnapshotWriter w = CsvSnapshotWriter.open(file)) {
            // just opening should write the header
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertEquals(CsvSnapshotWriter.HEADER, lines.get(0));
    }

    @Test
    void writerAppendsTwoRows(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.csv");
        PercentileSnapshot snap1 = new PercentileSnapshot(
                1713100000000L, 1, 12.4, 45.2, 102.1, 498.7, 1250.0, 0, 1250);
        PercentileSnapshot snap2 = new PercentileSnapshot(
                1713100001000L, 2, 11.8, 44.1, 99.8, 512.3, 1280.0, 2, 2530);

        try (CsvSnapshotWriter w = CsvSnapshotWriter.open(file)) {
            w.write(snap1);
            w.write(snap2);
        }

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size()); // header + 2 rows
        assertEquals(CsvSnapshotWriter.HEADER, lines.get(0));
        assertTrue(lines.get(1).startsWith("1713100000000,1,12.4,45.2,102.1,498.7,1250.0,0,1250"));
        assertTrue(lines.get(2).startsWith("1713100001000,2,11.8,44.1,99.8,512.3,1280.0,2,2530"));
    }

    @Test
    void formatRow_matchesContractSchema() {
        PercentileSnapshot snap = new PercentileSnapshot(
                1000L, 1, 10.0, 50.0, 100.0, 200.0, 500.0, 0, 500);
        String row = CsvSnapshotWriter.formatRow(snap);
        String[] cols = row.split(",");
        assertEquals(9, cols.length, "CSV must have exactly 9 columns per ADR-005");
    }

    @Test
    void writerCreatesParentDirectories(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested/deep/results.csv");
        try (CsvSnapshotWriter w = CsvSnapshotWriter.open(file)) {
            // just verify no exception
        }
        assertTrue(Files.exists(file));
    }
}
