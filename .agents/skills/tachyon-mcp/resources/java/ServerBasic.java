/*
 * Copyright (c) 2026 Konstantin Pavlov and contributors.
 */

import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import dev.tachyonmcp.core.server.json.NetworkntJsonSchemaValidator;

import java.util.List;
import java.util.UUID;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;

/**
 * Complete MCP server example with tool, resource, template, and prompt.
 */
public final class ServerBasic {

    public static void main(String... args) {
        var server = buildServer(8080);
        server.start();
        System.out.println("MCP server on http://localhost:" + server.port() + "/mcp");
    }

    static TachyonServer buildServer(int port) {
        var server = TachyonServer.builder()
            .info(it -> it.name("demo-server").version("1.0").description("Demo MCP server"))
            .capabilities(c -> c.tools(true).resources(true, true).prompts(true))
            .session(s -> s
                .sessionTtl(ofMinutes(5))
                .janitorInterval(ofSeconds(5))
                .sessionIdGenerator((channelContext, req) -> "sid_" + UUID.randomUUID())
            )
            .json(j ->
                j.inputSchemaValidator(NetworkntJsonSchemaValidator.INSTANCE)
                    .serde(JacksonPayloadSerde.INSTANCE)
            )
            .runtime(r -> r.shutdownGracePeriod(ofSeconds(5)))
            .port(port)
            .build();

        server.tools().register(b -> b.name("ping"), (ctx, request) -> ToolResult.text("pong"));
        server.resources()
            .register(
                ResourceDescriptor.of(
                    "config", "demo://config",
                    "Server configuration", "application/json"),
                (ctx, request) ->
                    TextResourceContents.of(request.uri(), "{\"mode\":\"production\"}", "application/json"))
            .registerTemplate(builder -> builder
                    .name("user-profile")
                    .uriTemplate("demo://users/{userId}/profile")
                    .description("User profile data")
                    .mimeType("application/json"),
                (ctx, request) -> {
                    var userId = request.params().get("userId").scalarValue();
                    return TextResourceContents.of(
                        request.uri(), "{\"userId\":\"" + userId + "\",\"name\":\"User\"}", "application/json");
                });
        server.prompts()
            .register(
                PromptDescriptor.of("greet", "Generates a greeting"),
                List.of(PromptMessage.user("Say hello")));

        return server;
    }

    private ServerBasic() {
    }
}
