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

import dev.engnotes.labs.commons.csv.CsvTableReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvTableReaderTest {

    @Test
    void readsQuotedFieldsWithCommasAndEscapedQuotes(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("quoted.csv");
        Files.writeString(csv, "strategy,note\n\"token-bucket, burst\",\"quote \"\"inside\"\"\"\n");

        CsvTableReader.Table table = CsvTableReader.read(csv);

        assertEquals("strategy", table.headers().get(0));
        assertEquals("token-bucket, burst", table.rows().get(0).get(0));
        assertEquals("quote \"inside\"", table.rows().get(0).get(1));
    }

    @Test
    void rejectsMalformedQuotedFields(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("bad.csv");
        Files.writeString(csv, "name\n\"bad\"tail\n");

        assertThrows(CsvTableReader.CsvParseException.class, () -> CsvTableReader.read(csv));
    }
}
