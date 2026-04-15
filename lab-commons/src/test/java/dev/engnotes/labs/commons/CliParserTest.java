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

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.cli.CliParseException;
import dev.engnotes.labs.commons.cli.CliParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CliParserTest {

    @Test
    void defaultsApplied_whenNoArgsGiven() {
        CliArgs args = CliParser.parse(new String[]{});
        assertFalse(args.isDeterministic());
        assertEquals(Duration.ofSeconds(30), args.duration());
        assertEquals(100, args.concurrency());
        assertEquals(Path.of("./results"), args.outputDir());
        assertEquals(Duration.ofSeconds(1), args.snapshotInterval());
    }

    @Test
    void deterministicFlag_parsed() {
        CliArgs args = CliParser.parse(new String[]{"--deterministic"});
        assertTrue(args.isDeterministic());
    }

    @Test
    void durationSeconds_parsed() {
        CliArgs args = CliParser.parse(new String[]{"--duration", "60s"});
        assertEquals(Duration.ofSeconds(60), args.duration());
    }

    @Test
    void durationMinutes_parsed() {
        CliArgs args = CliParser.parse(new String[]{"--duration", "2m"});
        assertEquals(Duration.ofMinutes(2), args.duration());
    }

    @Test
    void concurrency_parsed() {
        CliArgs args = CliParser.parse(new String[]{"--concurrency", "500"});
        assertEquals(500, args.concurrency());
    }

    @Test
    void outputDir_parsed() {
        CliArgs args = CliParser.parse(new String[]{"--output-dir", "/tmp/results"});
        assertEquals(Path.of("/tmp/results"), args.outputDir());
    }

    @Test
    void snapshotInterval_parsed() {
        CliArgs args = CliParser.parse(new String[]{"--snapshot-interval", "5s"});
        assertEquals(Duration.ofSeconds(5), args.snapshotInterval());
    }

    @Test
    void extraFlag_withValue_stored() {
        CliArgs args = CliParser.parse(new String[]{"--hedge-threshold", "p95"});
        assertEquals("p95", args.extra("--hedge-threshold"));
        assertEquals("p95", args.extra("hedge-threshold"));
    }

    @Test
    void extraFlag_booleanStyle_storedAsTrue() {
        CliArgs args = CliParser.parse(new String[]{"--some-flag"});
        assertTrue(args.hasExtra("some-flag"));
        assertEquals("true", args.extra("some-flag"));
    }

    @Test
    void multipleArgs_combinedCorrectly() {
        CliArgs args = CliParser.parse(new String[]{
                "--deterministic", "--duration", "30s", "--concurrency", "200",
                "--output-dir", "./out", "--snapshot-interval", "2s"
        });
        assertTrue(args.isDeterministic());
        assertEquals(Duration.ofSeconds(30), args.duration());
        assertEquals(200, args.concurrency());
        assertEquals(Path.of("./out"), args.outputDir());
        assertEquals(Duration.ofSeconds(2), args.snapshotInterval());
    }

    @Test
    void invalidConcurrency_throws() {
        assertThrows(CliParseException.class, () -> CliParser.parse(new String[]{"--concurrency", "abc"}));
    }

    @Test
    void concurrencyBelowOne_throws() {
        assertThrows(IllegalArgumentException.class, () -> CliParser.parse(new String[]{"--concurrency", "0"}));
    }

    @Test
    void flagWithoutValue_throws() {
        assertThrows(CliParseException.class, () -> CliParser.parse(new String[]{"--duration"}));
    }

    @Test
    void unknownPositionalArg_throws() {
        assertThrows(CliParseException.class, () -> CliParser.parse(new String[]{"bareword"}));
    }
}
