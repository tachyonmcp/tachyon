/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.core.server.TachyonServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

public interface McpSdkContract {

    int port();

    /** Starts a throwaway server in this test's session mode; the caller closes it. */
    TachyonServer startIsolatedServer(Consumer<TachyonServer> registrar);

    @Test
    default void shouldConnectInitializeAndPing() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port())
                .build();
        try (var client = McpClient.sync(transport).build()) {

            var initResult = client.initialize();
            assertThat(initResult).isNotNull();
            assertThat(initResult.serverInfo().name()).isEqualTo("tachyon-mcp");

            var pingResult = client.ping();
            assertThat(pingResult).isNotNull();
        }
    }

    @Test
    default void shouldListAndCallTool() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port())
                .build();
        try (var client = McpClient.sync(transport).build()) {
            client.initialize();

            var toolsResult = client.listTools();
            assertThat(toolsResult.tools()).hasSize(1);

            var tool = toolsResult.tools().getFirst();
            assertThat(tool.name()).isEqualTo("echo");
            assertThat(tool.description()).isEqualTo("Echo back the input message");

            var callRequest = McpSchema.CallToolRequest.builder("echo")
                    .arguments(Map.of("message", "hello"))
                    .build();
            var callResult = client.callTool(callRequest);
            assertThat(callResult.content()).hasSize(1);
            assertThat(callResult.content().getFirst()).isInstanceOf(McpSchema.TextContent.class);
            assertThat(((McpSchema.TextContent) callResult.content().getFirst()).text())
                    .isEqualTo("hello");
        }
    }

    @Test
    default void shouldListPromptsAndOmitResourcesCapabilityWhenOnlyPromptsRegistered() {
        try (var server = startIsolatedServer(s -> s.prompts()
                .register(
                        PromptDescriptor.of("greeting", "A greeting prompt"), List.of(PromptMessage.user("Hello!"))))) {
            var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + server.port())
                    .build();
            try (var client = McpClient.sync(transport).build()) {
                var initResult = client.initialize();

                assertThat(initResult.capabilities().prompts()).isNotNull();
                assertThat(initResult.capabilities().resources()).isNull();
                assertThat(client.listPrompts().prompts())
                        .extracting(McpSchema.Prompt::name)
                        .containsExactly("greeting");
            }
        }
    }
}
