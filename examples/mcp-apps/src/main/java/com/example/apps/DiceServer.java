/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.apps;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.apps.AppMeta;
import dev.tachyonmcp.extensions.apps.AppResource;
import dev.tachyonmcp.extensions.apps.AppsExtension;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls dice and renders the result in an MCP App view.
 */
public final class DiceServer {

    private static final Logger log = LoggerFactory.getLogger(DiceServer.class);

    static final String VIEW_URI = "ui://dice/roll";

    private DiceServer() {
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
        log.info("Dice await at http://{}:{}/mcp", server.host(), server.port());
    }

    static TachyonServer buildServer(String host, int port, @Nullable String allowedHost) {
        // snips-start: apps_enable
        var server = TachyonServer.builder()
            .host(host)
            .port(port)
            .network(n -> {
                if (allowedHost != null && !allowedHost.isBlank()) {
                    n.allowedHosts(allowedHost);
                }
            })
            .info(info -> info.name("dice").title("Dice").version("1.0"))
            .withExtension(AppsExtension.class, apps -> apps
                .app(res->res
                    .uri(VIEW_URI)                                   // ui:// scheme is mandatory
                    .name("dice_roll")
                    .title("Dice roll")
                    .description("Shows the rolled dice as pips")
                    .html(() -> classpath("apps/dice.html"))         // one self-contained HTML document
                    .meta(AppMeta.builder().prefersBorder(true).build())
                    .build()))
            .withTools(tools -> tools.register(
                tool -> tool.name("roll_dice")
                    .description("Roll one or more six-sided dice")
                    .inputSchema("""
                        {"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":10}}}
                        """)
                    .meta(AppsExtension.toolMeta(VIEW_URI)),          // host renders the result in the view
                (context, request) -> {
                    var count = request.arguments().intOr("count", 2);
                    var rolls = ThreadLocalRandom.current().ints(count, 1, 7).boxed().toList();
                    return ToolResult.structured(  // structuredContent for the view...
                        Map.of("rolls", rolls),
                        "Rolled " + rolls); // ...text for hosts without apps
                }))
            .build();
        // snips-end: apps_enable
        return server;
    }

    static String classpath(String path) {
        try (var in = DiceServer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing view: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
