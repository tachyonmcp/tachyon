/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestServers;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.node.JsonNodeFactory;

class SkillTestFixtures {

    static final Path filesystemSkillsDir = Path.of("./src/test/data/skills/");
    static final Path classpathSkillsDir = Path.of("./src/test/resources/skills/");

    static TachyonServer startServer(SkillsExtension extension) {
        return McpTestServers.start(builder -> builder.withExtensions(extension), it -> {});
    }

    static Mcp20260728Client createClient(int port) {
        return new Mcp20260728Client(port)
                .withExtensions(Map.of(SkillsExtension.ID, JsonNodeFactory.instance.objectNode()));
    }

    private SkillTestFixtures() {}
}
