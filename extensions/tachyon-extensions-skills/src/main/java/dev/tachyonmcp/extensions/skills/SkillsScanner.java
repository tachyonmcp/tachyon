/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import dev.tachyonmcp.core.server.features.resources.MimeTypes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

/** Builds {@link SkillsRegistry.Skill} instances from a skill directory's files. */
final class SkillsScanner {

    private SkillsScanner() {}

    /**
     * Builds a skill from its files (relative path → bytes), validating the {@code SKILL.md}
     * frontmatter and that the final skill-path segment equals the frontmatter {@code name}.
     *
     * @param skillPath the skill path
     * @param files the skill's files, keyed by path relative to the skill directory
     * @return the skill
     * @throws IllegalArgumentException when {@code SKILL.md} is missing or invalid
     */
    static SkillsRegistry.Skill buildSkill(String skillPath, Map<String, byte[]> files) {
        var skillMd = files.get("SKILL.md");
        if (skillMd == null) {
            throw new IllegalArgumentException("Skill '" + skillPath + "' is missing SKILL.md");
        }
        var frontmatter = FrontmatterParser.parse(skillMd);
        var name = String.valueOf(frontmatter.get("name"));
        var expected = lastSegment(skillPath);
        if (!expected.equals(name)) {
            throw new IllegalArgumentException(
                    "Skill path segment '" + expected + "' must equal frontmatter 'name' (" + name + ")");
        }
        var entries = files.entrySet().stream()
                .map(entry -> new SkillsRegistry.SkillFile(
                        entry.getKey(),
                        "skill://" + skillPath + "/" + entry.getKey(),
                        MimeTypes.guess(entry.getKey()),
                        digest(entry.getValue()),
                        entry.getValue().length))
                .sorted(Comparator.comparing(SkillsRegistry.SkillFile::relativePath))
                .toList();
        return new SkillsRegistry.Skill(skillPath, frontmatter, entries);
    }

    /** Returns the final {@code /}-separated segment of {@code path}. */
    static String lastSegment(String path) {
        var index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    /** Returns the SHA-256 digest of {@code bytes} formatted as {@code sha256:<hex>}. */
    static String digest(byte[] bytes) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(sha256.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
