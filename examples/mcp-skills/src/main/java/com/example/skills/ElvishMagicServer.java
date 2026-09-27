/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.skills;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.skills.ClasspathSkillsRegistry;
import dev.tachyonmcp.extensions.skills.FilesystemSkillsRegistry;
import dev.tachyonmcp.extensions.skills.SkillsExtension;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Serves bundled fictional Elvish Agent Skills over MCP.
 */
public final class ElvishMagicServer {

    private static final Logger log = LoggerFactory.getLogger(ElvishMagicServer.class);

    private ElvishMagicServer() {
    }

    /**
     * Starts the server on {@code HOST}:{@code PORT}, defaulting to {@code localhost:8080}.
     * Set {@code ALLOWED_HOST} to accept an extra {@code Host} authority (e.g. a Docker-bridge caller).
     *
     * @param args unused
     */
    public static void main(String... args) {
        var server = buildServer(
            System.getenv().getOrDefault("HOST", "localhost"),
            Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
            System.getenv("ALLOWED_HOST")
        );
        server.start();
        log.info("Elvish skills await at http://{}:{}/mcp", server.host(), server.port());
    }

    static TachyonServer buildServer(String host, int port, @Nullable String allowedHost) {
        return TachyonServer.builder()
            .host(host)
            .port(port)
            .network(n -> {
                if (allowedHost != null && !allowedHost.isBlank()) {
                    n.allowedHosts(allowedHost);
                }
            })
            .info(info -> info.name("elvish-magic")
                .title("Elvish Magic Library")
                .description("MCP server for fictional Elvish Agent Skills")
                .version("1.0"))
            .withExtension(SkillsExtension.class, skills -> skills
                .cacheScope("public")
                .registry(new ClasspathSkillsRegistry("skills"))
                .registry(new FilesystemSkillsRegistry(Path.of(System.getProperty("user.dir"),"src/data/skills"))))
            .build();
    }
}
