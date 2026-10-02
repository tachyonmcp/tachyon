package dev.tachyonmcp.docs.extensions.skills;

// snips-start: skills_enable
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.skills.ClasspathSkillsRegistry;
import dev.tachyonmcp.extensions.skills.FilesystemSkillsRegistry;
import dev.tachyonmcp.extensions.skills.SkillsExtension;
import java.nio.file.Path;

public final class SkillsServer {
    public static void main(String[] args) {
        var server = TachyonServer.builder()
                .withExtension(SkillsExtension.class, skills -> skills
                        .registry(new FilesystemSkillsRegistry(Path.of("skills")))      // every subdirectory with a SKILL.md
                        .registry(new ClasspathSkillsRegistry("bundled-skills")))       // same, packaged inside the jar
                .port(8080)
                .build();
        server.start();
    }
}
// snips-end: skills_enable
