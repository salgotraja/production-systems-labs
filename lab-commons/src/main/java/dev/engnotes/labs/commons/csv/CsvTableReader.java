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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CsvTableReader {

    private CsvTableReader() {}

    public record Table(List<String> headers, List<List<String>> rows) {
        public Table {
            headers = List.copyOf(headers);
            rows = rows.stream()
                    .map(List::copyOf)
                    .toList();
        }
    }

    public static final class CsvParseException extends IOException {
        public CsvParseException(String message) {
            super(message);
        }
    }

    public static Table read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return new Table(List.of(), List.of());
        }

        List<String> headers = parseLine(lines.get(0), 1);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(parseLine(lines.get(i), i + 1));
        }
        return new Table(headers, rows);
    }

    static List<String> parseLine(String line, int lineNumber) throws CsvParseException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean afterQuote = false;
        boolean fieldStarted = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        afterQuote = true;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }

            if (afterQuote) {
                if (ch == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    afterQuote = false;
                    fieldStarted = false;
                    continue;
                }
                throw malformed(lineNumber);
            }

            if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (ch == '"') {
                if (fieldStarted) {
                    throw malformed(lineNumber);
                }
                inQuotes = true;
                fieldStarted = true;
            } else {
                field.append(ch);
                fieldStarted = true;
            }
        }

        if (inQuotes) {
            throw malformed(lineNumber);
        }
        fields.add(field.toString());
        return fields;
    }

    private static CsvParseException malformed(int lineNumber) {
        return new CsvParseException("Malformed CSV quote at line " + lineNumber);
    }
}
