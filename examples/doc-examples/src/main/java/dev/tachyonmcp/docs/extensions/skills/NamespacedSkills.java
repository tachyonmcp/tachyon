package dev.tachyonmcp.docs.extensions.skills;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.skills.ClasspathSkillsRegistry;
import dev.tachyonmcp.extensions.skills.FilesystemSkillsRegistry;
import dev.tachyonmcp.extensions.skills.SkillsExtension;
import java.nio.file.Path;

final class NamespacedSkills {

    private NamespacedSkills() {}

    static TachyonServer start() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: skills_namespaced
                .withExtension(SkillsExtension.class, skills -> skills
                        .registry(new FilesystemSkillsRegistry(Path.of("skills/git-workflow"), "team/git-workflow"))
                        .registry(new ClasspathSkillsRegistry("skills/pdf-processing", "acme/pdf-processing")))
                // snips-end: skills_namespaced
                .build();
        server.start();
        return server;
    }
}
