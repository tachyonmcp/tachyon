/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.ElicitationRequest;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.McpTestServers;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeclarativeElicitationTest {
    static class CityTools {
        private static final JsonSchema CITY_SCHEMA = JsonSchema.unchecked("""
                {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
                """);

        @McpTool(name = "choose-city", description = "Ask the user to choose a forecast city")
        public ToolResult chooseCity(InteractionContext context) {
            final var result = context.client()
                    .elicitation()
                    .create(ElicitationRequest.builder()
                            .message("Choose a forecast city")
                            .requestedSchema(CITY_SCHEMA)
                            .build())
                    .join();
            return switch (result.action()) {
                case ACCEPT -> ToolResult.text("Selected " + result.content().stringValue("city"));
                case DECLINE -> ToolResult.error("City selection declined");
                case CANCEL -> ToolResult.error("City selection cancelled");
            };
        }
    }

    @ParameterizedTest
    @EnumSource(McpSchema.ElicitResult.Action.class)
    void annotatedToolCompletesClientElicitationRoundTrip(McpSchema.ElicitResult.Action action) {
        final var requests = new ConcurrentLinkedQueue<McpSchema.ElicitRequest>();
        try (final var server = McpTestServers.start(
                builder -> builder.session(session -> session.enabled(true))
                        .annotations(annotations -> annotations.register(new CityTools())),
                ignored -> {})) {
            final var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + server.port())
                    .build();
            try (final var client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(10))
                    .elicitation(request -> {
                        requests.add(request);
                        return new McpSchema.ElicitResult(
                                action,
                                action == McpSchema.ElicitResult.Action.ACCEPT ? Map.of("city", "Paris") : null);
                    })
                    .build()) {
                client.initialize();
                final var tools = client.listTools().tools();
                assertThat(tools).singleElement().satisfies(tool -> {
                    assertThat(tool.name()).isEqualTo("choose-city");
                    assertThat(tool.description()).isEqualTo("Ask the user to choose a forecast city");
                    assertThat(tool.inputSchema()).isEqualTo(Map.of("type", "object", "properties", Map.of()));
                });

                final var result = client.callTool(new McpSchema.CallToolRequest("choose-city", Map.of()));
                assertThat(requests)
                        .singleElement()
                        .isEqualTo(new McpSchema.ElicitFormRequest(
                                "Choose a forecast city",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("city", Map.of("type", "string")),
                                        "required",
                                        List.of("city")),
                                null));
                final var expected =
                        switch (action) {
                            case ACCEPT -> "Selected Paris";
                            case DECLINE -> "City selection declined";
                            case CANCEL -> "City selection cancelled";
                        };
                assertThat(result.content()).containsExactly(new McpSchema.TextContent(expected));
                assertThat(result.isError())
                        .isEqualTo(action == McpSchema.ElicitResult.Action.ACCEPT ? null : Boolean.TRUE);
                assertThat(result.structuredContent()).isNull();
                assertThat(result.meta()).isNull();
            }
        }
    }
}
