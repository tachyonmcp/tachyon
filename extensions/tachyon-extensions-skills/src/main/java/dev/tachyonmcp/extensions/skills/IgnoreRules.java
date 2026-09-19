/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filters skill files matched by gitignore-style patterns from the {@code .mcpignore} resource
 * bundled on the classpath.
 */
final class IgnoreRules {

    private static final Logger logger = LoggerFactory.getLogger(IgnoreRules.class);
    private static final String RESOURCE = "META-INF/dev/tachyonmcp/extensions/skills/.mcpignore";
    private static final List<Rule> RULES = load();

    private IgnoreRules() {}

    /** Removes files whose relative path matches an ignore pattern from a relative-path-keyed map. */
    static Map<String, byte[]> filter(Map<String, byte[]> files) {
        if (RULES.isEmpty()) {
            return files;
        }
        var filtered = new LinkedHashMap<String, byte[]>();
        files.forEach((relativePath, bytes) -> {
            if (!matches(relativePath)) {
                filtered.put(relativePath, bytes);
            }
        });
        return filtered;
    }

    /** Returns whether a relative path matches an ignore pattern. */
    static boolean matches(String relativePath) {
        return matches(relativePath, RULES);
    }

    static boolean matches(String relativePath, List<Rule> rules) {
        var path = Path.of(relativePath);
        for (var rule : rules) {
            if (rule.anchored()) {
                if (rule.directory()) {
                    for (var i = 1; i <= path.getNameCount(); i++) {
                        if (rule.matcher().matches(path.subpath(0, i))) {
                            return true;
                        }
                    }
                } else if (rule.matcher().matches(path)) {
                    return true;
                }
            } else {
                for (var i = 0; i < path.getNameCount(); i++) {
                    if (rule.matcher().matches(path.getName(i))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Loads gitignore-style patterns from the {@code .mcpignore} resource bundled on the
     * classpath: blank lines and {@code #} comments are skipped; a pattern containing {@code /}
     * matches the file's full relative path, otherwise it matches any path segment (so it also
     * excludes everything under a matching directory).
     */
    private static List<Rule> load() {
        var loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = IgnoreRules.class.getClassLoader();
        }
        try (var in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Failed to load '{}'; ignore patterns disabled", RESOURCE, e);
            return List.of();
        }
    }

    static List<Rule> parse(String content) {
        var fileSystem = FileSystems.getDefault();
        return content.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> parseRule(fileSystem, line))
                .filter(Objects::nonNull)
                .toList();
    }

    private static @Nullable Rule parseRule(FileSystem fileSystem, String line) {
        var anchored = line.startsWith("/");
        var pattern = anchored ? line.substring(1) : line;
        var directory = pattern.endsWith("/");
        pattern = directory ? pattern.substring(0, pattern.length() - 1) : pattern;
        anchored = anchored || pattern.contains("/");
        try {
            return new Rule(fileSystem.getPathMatcher("glob:" + pattern), anchored, directory);
        } catch (PatternSyntaxException e) {
            logger.warn("Skipping malformed .mcpignore pattern '{}': {}", line, e.getMessage());
            return null;
        }
    }

    record Rule(PathMatcher matcher, boolean anchored, boolean directory) {}
}
