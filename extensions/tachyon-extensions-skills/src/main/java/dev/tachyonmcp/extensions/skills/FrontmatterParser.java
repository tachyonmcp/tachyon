/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parses the YAML frontmatter block of a {@code SKILL.md} into a JSON-compatible map. */
class FrontmatterParser {

    private static final String DELIMITER = "---";

    private FrontmatterParser() {}

    private static Yaml yaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /**
     * Parses the frontmatter of a {@code SKILL.md}, requiring a non-blank {@code name} and
     * {@code description}.
     *
     * @param skillMd the raw {@code SKILL.md} bytes
     * @return the frontmatter fields
     * @throws IllegalArgumentException when the frontmatter is missing, invalid, or incomplete
     */
    static Map<String, Object> parse(byte[] skillMd) {
        var text = new String(skillMd, StandardCharsets.UTF_8);
        if (!text.startsWith(DELIMITER)) {
            throw new IllegalArgumentException("SKILL.md must start with YAML frontmatter delimited by '---'");
        }
        var end = text.indexOf("\n" + DELIMITER, 3);
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md frontmatter is not closed with '---'");
        }
        Object loaded;
        try {
            loaded = yaml().load(text.substring(3, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SKILL.md YAML frontmatter: " + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("SKILL.md frontmatter must be a YAML mapping");
        }
        var frontmatter = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> frontmatter.put(String.valueOf(key), value));
        if (!(frontmatter.get("name") instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("SKILL.md frontmatter must declare a non-blank 'name'");
        }
        if (!(frontmatter.get("description") instanceof String description) || description.isBlank()) {
            throw new IllegalArgumentException("SKILL.md frontmatter must declare a non-blank 'description'");
        }
        return frontmatter;
    }
}
