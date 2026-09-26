/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the engine protocol-neutral, so it can move to its own module and serve other protocol
 * bindings (A2A) without dragging MCP along.
 */
class EngineBoundaryTest {

    private static final Path ENGINE_SOURCES = Path.of("src/main/java/dev/tachyonmcp/extensions/tasks/engine");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)");

    private static final List<String> ALLOWED = List.of(
            "java.",
            "org.jspecify.",
            "org.slf4j.",
            "dev.tachyonmcp.api.annotations.",
            "dev.tachyonmcp.api.runtime.",
            "dev.tachyonmcp.api.server.features.",
            "dev.tachyonmcp.api.server.domain.TaskResult",
            "dev.tachyonmcp.core.server.features.ChangeSupport",
            "dev.tachyonmcp.core.server.features.Pagination",
            "dev.tachyonmcp.core.server.internal.AbstractJanitor",
            "dev.tachyonmcp.extensions.tasks.engine.");

    @Test
    void engineImportsNoProtocolTypes() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.list(ENGINE_SOURCES)) {
            offenders = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(EngineBoundaryTest::imports)
                    .filter(entry -> ALLOWED.stream().noneMatch(entry.substring(entry.indexOf(' ') + 1)::startsWith))
                    .toList();
        }

        assertThat(ENGINE_SOURCES).isDirectory();
        assertThat(offenders)
                .as("engine must not import MCP or other protocol types")
                .isEmpty();
    }

    private static Stream<String> imports(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(IMPORT::matcher)
                    .filter(java.util.regex.Matcher::find)
                    .map(matcher -> file.getFileName() + " " + matcher.group(1));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
