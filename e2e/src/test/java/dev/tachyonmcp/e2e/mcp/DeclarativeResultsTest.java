/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;

import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarativeResultsTest {
    private static final String LOGO =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9WQAAAAASUVORK5CYII=";

    static class Service {
        private final Path logo;

        Service(Path logo) {
            this.logo = logo;
        }

        @McpResource(uri = "app://logo", mimeType = "image/png")
        public byte[] logo() throws IOException {
            return Files.readAllBytes(logo);
        }

        @McpPrompt(name = "review-conversation")
        public List<PromptMessage> conversation(String concern) {
            return List.of(
                    PromptMessage.user("Review this code for " + concern + "."),
                    PromptMessage.of(Role.ASSISTANT, TextContent.of("Share the code you want reviewed.")));
        }

        @McpTool(description = "Say hello to someone")
        public ToolResult greet(String name) {
            if (name.isBlank()) {
                return ToolResult.error("Provide a non-blank name.");
            }
            return ToolResult.text("Hello, " + name + "!");
        }

        @McpTool(name = "choose-city")
        public ToolResult chooseCity() {
            final var schema = JsonSchema.unchecked("""
                    {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
                    """);
            return ToolResult.inputRequired(
                    Map.of("city", FormInputRequest.of("Choose a forecast city", schema)), "forecast-draft-42");
        }
    }

    @TempDir
    Path directory;

    private TachyonServer server;

    @BeforeEach
    void start() throws IOException {
        final var logo =
                Files.write(directory.resolve("logo.png"), Base64.getDecoder().decode(LOGO));
        server = McpTestServers.start(
                builder -> builder.annotations(annotations -> annotations.register(new Service(logo))), ignored -> {});
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void binaryResourceEncodesRawBytesOnce() throws Exception {
        try (final var client = McpTestClients.latest(server.port())) {
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"app://logo"}}
                    """)).isSuccess().hasId(1).hasResult("""
                    {"contents":[{"uri":"app://logo","mimeType":"image/png","blob":"%s"}],
                     "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    """.formatted(LOGO));
        }
    }

    @Test
    void explicitPromptMessagesPreserveMixedRoles() throws Exception {
        try (final var client = McpTestClients.latest(server.port())) {
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"prompts/get",
                     "params":{"name":"review-conversation","arguments":{"concern":"security"}}}
                    """)).isSuccess().hasId(2).hasResult("""
                    {"messages":[
                      {"role":"user","content":{"type":"text","text":"Review this code for security."}},
                      {"role":"assistant","content":{"type":"text","text":"Share the code you want reviewed."}}
                     ],"resultType":"complete"}
                    """);
        }
    }

    @Test
    void explicitToolResultsPreserveSuccessAndError() throws Exception {
        try (final var client = McpTestClients.latest(server.port())) {
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":"Ada"}}}
                    """)).isSuccess().hasId(3).hasResult("""
                    {"content":[{"type":"text","text":"Hello, Ada!"}],"resultType":"complete"}
                    """);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":4,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":""}}}
                    """)).isSuccess().hasId(4).hasResult("""
                    {"content":[{"type":"text","text":"Provide a non-blank name."}],
                     "isError":true,"resultType":"complete"}
                    """);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"greet","arguments":{}}}
                    """)).isJsonRpcError().hasId(5).hasErrorCode(-32602);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":6,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":42}}}
                    """)).isJsonRpcError().hasId(6).hasErrorCode(-32602);
        }
    }

    @Test
    void inputRequiredPreservesFormAndRequestState() throws Exception {
        try (final var client = McpTestClients.latest(server.port())) {
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"choose-city","arguments":{}}}
                    """)).isSuccess().hasId(7).hasResult("""
                    {"resultType":"input_required",
                     "inputRequests":{"city":{"method":"elicitation/create","params":{
                       "message":"Choose a forecast city",
                       "requestedSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
                     }}},"requestState":"forecast-draft-42"}
                    """);
        }
    }
}
