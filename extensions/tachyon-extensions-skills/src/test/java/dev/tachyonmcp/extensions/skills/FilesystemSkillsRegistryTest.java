/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import static dev.tachyonmcp.extensions.skills.SkillTestFixtures.filesystemSkillsDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSkillsRegistryTest {

    @Test
    void scansDirectoryOfSkills() {
        var registry = new FilesystemSkillsRegistry(filesystemSkillsDir);

        assertThat(registry.skills())
                .extracting(SkillsRegistry.Skill::skillPath)
                .containsExactlyInAnyOrder("git-workflow", "read-file");

        var git = registry.skills().getFirst();
        assertThat(git.skillUri()).isEqualTo("skill://git-workflow/SKILL.md");
        assertThat(git.frontmatter()).containsEntry("name", "git-workflow");
        assertThat(git.files())
                .extracting(SkillsRegistry.SkillFile::relativePath)
                .containsExactly("SKILL.md", "references/BRANCHING.md");

        final var skillFile = git.files().getFirst();
        assertThat(skillFile.uri()).isEqualTo("skill://git-workflow/SKILL.md");
        assertThat(skillFile.mimeType()).isEqualTo("text/markdown");
        assertThat(skillFile.digest())
                .isEqualTo("sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67");
    }

    @Test
    void readsFileBytesByUri() {
        var registry = new FilesystemSkillsRegistry(filesystemSkillsDir);

        var content = new String(registry.readFile("skill://git-workflow/SKILL.md"), StandardCharsets.UTF_8);
        assertThat(content).contains("Follow this team's Git conventions");

        assertThat(registry.readFile("skill://git-workflow/missing.md")).isNull();
    }

    @Test
    void skipsSubdirectoriesWithoutSkillMd(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("not-a-skill"));
        Files.writeString(tempDir.resolve("not-a-skill/README.md"), "no skill here");

        var registry = new FilesystemSkillsRegistry(tempDir);

        assertThat(registry.skills()).isEmpty();
    }

    @Test
    void rejectsDirectoryWithoutSkillMdWhenStrict(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "no skill here");

        assertThatThrownBy(() -> new FilesystemSkillsRegistry(tempDir, "git-workflow"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void rejectsNameMismatchBetweenPathAndFrontmatter() {
        assertThatThrownBy(() -> new FilesystemSkillsRegistry(filesystemSkillsDir.resolve("git-workflow"), "renamed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal frontmatter 'name'");
    }

    @Test
    void servesSingleSkillUnderNestedPath() {
        var registry = new FilesystemSkillsRegistry(filesystemSkillsDir.resolve("git-workflow"), "team/git-workflow");

        assertThat(registry.skills())
                .extracting(SkillsRegistry.Skill::skillPath)
                .containsExactly("team/git-workflow");
        assertThat(registry.skills().get(0).skillUri()).isEqualTo("skill://team/git-workflow/SKILL.md");
        assertThat(registry.readFile("skill://team/git-workflow/references/BRANCHING.md"))
                .isNotEmpty();
    }

    @Test
    void ignoresFilesMatchingMcpignorePatterns(@TempDir Path tempDir) throws Exception {
        var skillDir = tempDir.resolve("junky-skill");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: junky-skill
                description: A skill directory littered with OS junk files
                ---
                # Junky Skill
                """);
        Files.writeString(skillDir.resolve(".DS_Store"), "junk");
        Files.writeString(skillDir.resolve("Thumbs.db"), "junk");

        var registry = new FilesystemSkillsRegistry(skillDir, "junky-skill");

        assertThat(registry.skills()).hasSize(1);
        assertThat(registry.skills().getFirst().files())
                .extracting(SkillsRegistry.SkillFile::relativePath)
                .containsExactly("SKILL.md");
        assertThat(registry.readFile("skill://junky-skill/SKILL.md")).isNotEmpty();
    }

    @Test
    void returnsBinaryBytesForNonTextFiles() {
        var registry = new FilesystemSkillsRegistry(filesystemSkillsDir);

        var script = registry.skills().stream()
                .filter(skill -> skill.skillPath().equals("git-workflow"))
                .findFirst()
                .orElseThrow()
                .files()
                .stream()
                .filter(file -> file.relativePath().equals("references/BRANCHING.md"))
                .findFirst()
                .orElseThrow();
        assertThat(script.mimeType()).isEqualTo("text/markdown");
        assertThat(script.digest())
                .isEqualTo("sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e");
        assertThat(new String(registry.readFile(script.uri()), StandardCharsets.UTF_8))
                .contains("Branching Guide");
    }
}
