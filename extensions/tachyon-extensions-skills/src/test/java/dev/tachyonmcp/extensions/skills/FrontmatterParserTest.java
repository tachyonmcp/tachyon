/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrontmatterParserTest {

    @Test
    void parsesNameDescriptionAndExtraFields() {
        var frontmatter = FrontmatterParser.parse(skillMd("""
                ---
                name: git-workflow
                description: Follow this team's Git conventions
                license: Apache-2.0
                metadata:
                  version: "2.1.0"
                ---
                # Body
                """));

        assertThat(frontmatter)
                .containsEntry("name", "git-workflow")
                .containsEntry("description", "Follow this team's Git conventions")
                .containsEntry("license", "Apache-2.0");
        assertThat(frontmatter.get("metadata")).isInstanceOf(Map.class);
    }

    @Test
    void parsesMultilineDescription() {
        var frontmatter = FrontmatterParser.parse(skillMd("""
                ---
                name: pdf-processing
                description: |
                  Extract, fill, and assemble PDF documents.
                  Follow the templates.
                ---
                # Body
                """));

        assertThat(frontmatter.get("description"))
                .isEqualTo("Extract, fill, and assemble PDF documents.\nFollow the templates.\n");
    }

    @Test
    void rejectsMissingFrontmatter() {
        assertThatThrownBy(() -> FrontmatterParser.parse("""
                # Body without frontmatter
                """.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontmatter");
    }

    @Test
    void rejectsUnclosedFrontmatter() {
        assertThatThrownBy(() -> FrontmatterParser.parse(skillMd("""
                ---
                name: git-workflow
                description: missing closing delimiter
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    void rejectsInvalidYaml() {
        assertThatThrownBy(() -> FrontmatterParser.parse(skillMd("""
                ---
                name: [unclosed
                description: x
                ---
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YAML");
    }

    @Test
    void rejectsMissingName() {
        assertThatThrownBy(() -> FrontmatterParser.parse(skillMd("""
                ---
                description: no name here
                ---
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'name'");
    }

    @Test
    void rejectsMissingDescription() {
        assertThatThrownBy(() -> FrontmatterParser.parse(skillMd("""
                ---
                name: git-workflow
                ---
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'description'");
    }

    private static byte[] skillMd(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
