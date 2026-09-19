/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Source of skills for the {@code io.modelcontextprotocol/skills} extension
 * (<a href="https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2640">SEP-2640</a>).
 *
 * <p>An implementation scans skill directories (each a directory containing a {@code SKILL.md}
 * with YAML frontmatter) and serves their files. Default implementations: {@link FilesystemSkillsRegistry}
 * and {@link ClasspathSkillsRegistry}.
 */
public interface SkillsRegistry {

    /** Returns the skills served by this registry, in scan order. */
    List<Skill> skills();

    /**
     * Returns the raw bytes of the skill file with the given resource URI
     * ({@code skill://<skill-path>/<file-path>}), or {@code null} when unknown.
     *
     * @param fileUri the file's resource URI, as in {@link SkillFile#uri()}
     * @return the file bytes, or {@code null} if no such file is served
     */
    byte @Nullable [] readFile(String fileUri);

    /**
     * A single skill: the directory {@code <skill-path>} whose files are exposed as
     * {@code skill://<skill-path>/...} resources. {@code frontmatter} is the parsed
     * {@code SKILL.md} YAML frontmatter; {@code files} lists every file with its SHA-256 digest.
     *
     * @param skillPath the skill path; its final segment equals the frontmatter {@code name}
     * @param frontmatter the verbatim {@code SKILL.md} YAML frontmatter as a JSON-compatible map
     * @param files every file of the skill, sorted by relative path
     */
    record Skill(String skillPath, Map<String, Object> frontmatter, List<SkillFile> files) {

        /** Returns the resource URI of the skill's {@code SKILL.md}. */
        public String skillUri() {
            return "skill://" + skillPath + "/SKILL.md";
        }
    }

    /**
     * A file inside a skill.
     *
     * @param relativePath the path relative to the skill directory
     * @param uri the resource URI ({@code skill://<skill-path>/<file-path>})
     * @param mimeType the file's MIME type
     * @param digest the SHA-256 digest formatted as {@code sha256:<hex>}
     * @param size the raw file size in bytes
     */
    record SkillFile(String relativePath, String uri, String mimeType, String digest, long size) {}
}
