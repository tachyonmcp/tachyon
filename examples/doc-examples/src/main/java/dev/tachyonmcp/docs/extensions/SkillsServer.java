package dev.tachyonmcp.docs.extensions;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.skills.FilesystemSkillsRegistry;
import dev.tachyonmcp.extensions.skills.SkillsExtension;
import java.nio.file.Path;

public final class SkillsServer {

    private SkillsServer() {}

    public static void main(String[] args) {
        // snips-start: extensions_add_skills
        var server = TachyonServer.builder()
                .withExtension(SkillsExtension.class, skills -> skills
                        .registry(new FilesystemSkillsRegistry(Path.of("skills"))))
                .port(8080)
                .build();
        server.start();
        // snips-end: extensions_add_skills
    }
}
