/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared storage and scanning for the default {@link SkillsRegistry} implementations. */
public abstract class BaseSkillsRegistry implements SkillsRegistry {

    private static final Logger logger = LoggerFactory.getLogger(BaseSkillsRegistry.class);

    private final Map<String, SkillsRegistry.Skill> skillsByPath = new LinkedHashMap<>();
    private final Map<String, byte[]> bytesByUri = new HashMap<>();
    private final Map<String, Path> pathsByUri = new HashMap<>();

    @Override
    public final List<SkillsRegistry.Skill> skills() {
        return List.copyOf(skillsByPath.values());
    }

    @Override
    public final byte @Nullable [] readFile(String fileUri) {
        var cached = bytesByUri.get(fileUri);
        if (cached != null) {
            return cached;
        }
        var path = pathsByUri.get(fileUri);
        if (path == null) {
            return null;
        }
        // Filesystem-backed files are re-read on demand rather than cached at scan time; a file
        // edited on disk after scanning can therefore diverge from the digest reported by skills().
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Scans a directory of skills: every subdirectory containing a {@code SKILL.md} becomes a skill. */
    protected final void addSkillDir(Path root) {
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(child -> child.getFileName().toString()))
                    .forEach(child -> addPath(child, child.getFileName().toString(), this::registerOrSkip));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Scans a single skill directory; rethrows {@link IllegalArgumentException} on invalid skills. */
    protected final void addSkill(Path skillDir, String skillPath) {
        addPath(skillDir, skillPath, this::registerStrict);
    }

    /** Registers a skill from pre-read files (relative path → bytes); rethrows on an invalid skill. */
    protected final void addFilesStrict(String skillPath, Map<String, byte[]> files) {
        registerFiles(skillPath, files, this::registerStrict);
    }

    /** Registers a skill from pre-read files (relative path → bytes); skips and logs an invalid skill. */
    protected final void addFilesLenient(String skillPath, Map<String, byte[]> files) {
        registerFiles(skillPath, files, this::registerOrSkip);
    }

    private void registerFiles(
            String skillPath,
            Map<String, byte[]> files,
            BiFunction<String, Map<String, byte[]>, SkillsRegistry.@Nullable Skill> register) {
        var filtered = IgnoreRules.filter(files);
        var skill = register.apply(skillPath, filtered);
        if (skill == null) {
            return;
        }
        for (var file : skill.files()) {
            bytesByUri.put(file.uri(), filtered.get(file.relativePath()));
        }
    }

    private void addPath(
            Path skillDir,
            String skillPath,
            BiFunction<String, Map<String, byte[]>, SkillsRegistry.@Nullable Skill> register) {
        try (var walk = Files.walk(skillDir)) {
            var relativePaths = new HashMap<String, Path>();
            var files = new HashMap<String, byte[]>();
            walk.filter(Files::isRegularFile).filter(Files::isReadable).forEach(file -> {
                var relative = skillDir.relativize(file).toString().replace('\\', '/');
                if (IgnoreRules.matches(relative)) {
                    return;
                }
                relativePaths.put(relative, file);
                try {
                    files.put(relative, Files.readAllBytes(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            var skill = register.apply(skillPath, files);
            if (skill != null) {
                for (var file : skill.files()) {
                    pathsByUri.put(file.uri(), relativePaths.get(file.relativePath()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Builds and registers the skill, rethrowing {@link IllegalArgumentException} on an invalid skill. */
    private SkillsRegistry.Skill registerStrict(String skillPath, Map<String, byte[]> files) {
        var skill = SkillsScanner.buildSkill(skillPath, files);
        skillsByPath.put(skill.skillPath(), skill);
        return skill;
    }

    /** Builds and registers the skill, or returns {@code null} and logs a warning when invalid. */
    private SkillsRegistry.@Nullable Skill registerOrSkip(String skillPath, Map<String, byte[]> files) {
        try {
            return registerStrict(skillPath, files);
        } catch (IllegalArgumentException e) {
            logger.warn("Skipping skill directory '{}': {}", skillPath, e.getMessage());
            return null;
        }
    }
}
