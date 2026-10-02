package dev.tachyonmcp.docs.extensions.skills;

import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.skills.ClasspathSkillsRegistry;
import dev.tachyonmcp.extensions.skills.SkillsExtension;

final class RequiredNegotiation {

    private RequiredNegotiation() {}

    static TachyonServer start() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: skills_required_negotiation
                .withExtension(SkillsExtension.class, skills -> skills
                        .registry(new ClasspathSkillsRegistry("skills"))
                        .negotiation(ExtensionNegotiation.REQUIRED))
                // snips-end: skills_required_negotiation
                .build();
        server.start();
        return server;
    }
}
