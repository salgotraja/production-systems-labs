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
package dev.engnotes.labs.commons.manifest;

import dev.engnotes.labs.commons.cli.CliArgs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ExperimentManifest {

    private ExperimentManifest() {}

    public record CsvArtifact(String name, Path path) {}

    public static void write(
            int postNumber,
            String title,
            CliArgs args,
            String[] commandArgs,
            List<CsvArtifact> csvArtifacts,
            Path manifestPath) throws IOException {

        Files.createDirectories(manifestPath.getParent() != null ? manifestPath.getParent() : Path.of("."));
        Files.writeString(
                manifestPath,
                toJson(postNumber, title, args, commandArgs, csvArtifacts),
                StandardCharsets.UTF_8);
    }

    static String toJson(
            int postNumber,
            String title,
            CliArgs args,
            String[] commandArgs,
            List<CsvArtifact> csvArtifacts) throws IOException {

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, "post_number", postNumber).append(",\n");
        field(json, "title", title).append(",\n");
        field(json, "timestamp", Instant.now().toString()).append(",\n");
        field(json, "deterministic", args.isDeterministic()).append(",\n");
        field(json, "duration", args.duration().toString()).append(",\n");
        field(json, "concurrency", args.concurrency()).append(",\n");
        field(json, "snapshot_interval", args.snapshotInterval().toString()).append(",\n");
        field(json, "output_dir", args.outputDir().toString()).append(",\n");
        field(json, "java_version", System.getProperty("java.version")).append(",\n");
        field(json, "java_vendor", System.getProperty("java.vendor")).append(",\n");
        field(json, "os", System.getProperty("os.name") + " " + System.getProperty("os.version")).append(",\n");
        field(json, "os_arch", System.getProperty("os.arch")).append(",\n");
        field(json, "cpu_count", Runtime.getRuntime().availableProcessors()).append(",\n");
        field(json, "git_sha", gitSha()).append(",\n");

        json.append("  \"command_args\": [");
        for (int i = 0; i < commandArgs.length; i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append(quote(commandArgs[i]));
        }
        json.append("],\n");

        json.append("  \"csv_artifacts\": [\n");
        for (int i = 0; i < csvArtifacts.size(); i++) {
            CsvArtifact artifact = csvArtifacts.get(i);
            Path golden = Path.of("golden", "post" + postNumber, artifact.path().getFileName().toString());
            json.append("    {");
            json.append("\"name\": ").append(quote(artifact.name())).append(", ");
            json.append("\"file\": ").append(quote(artifact.path().getFileName().toString())).append(", ");
            json.append("\"sha256\": ").append(quote(sha256(artifact.path()))).append(", ");
            json.append("\"golden_file\": ").append(quote(golden.toString())).append(", ");
            json.append("\"golden_sha256\": ").append(Files.exists(golden) ? quote(sha256(golden)) : "null").append(", ");
            json.append("\"golden_match\": ").append(goldenMatch(artifact.path(), golden));
            json.append("}");
            if (i < csvArtifacts.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static StringBuilder field(StringBuilder json, String key, String value) {
        return json.append("  ").append(quote(key)).append(": ").append(quote(value));
    }

    private static StringBuilder field(StringBuilder json, String key, int value) {
        return json.append("  ").append(quote(key)).append(": ").append(value);
    }

    private static StringBuilder field(StringBuilder json, String key, boolean value) {
        return json.append("  ").append(quote(key)).append(": ").append(value);
    }

    private static String goldenMatch(Path actual, Path golden) throws IOException {
        if (!Files.exists(golden)) {
            return quote("absent");
        }
        return Boolean.toString(sha256(actual).equals(sha256(golden)));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String gitSha() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "unknown";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
