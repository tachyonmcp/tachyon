/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.e2e.mcp.AbstractSchemaValidationContractTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.List;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.api.Test;

class SchemaValidationContractTest extends AbstractSchemaValidationContractTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Override
    protected Mcp20260728Client readyClient() {
        return createModernTestClient();
    }

    @Test
    protected void shouldRejectToolCallWithMissingRequiredField() throws Exception {
        startServer(
                b -> b.json(j -> j.inputSchemaValidator(VALIDATOR).outputSchemaValidator(VALIDATOR)),
                s -> s.tools().register(validatedTool(), (context, request) -> ToolResult.text("ok")));

        try (var client = readyClient()) {
            var response = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"validated","arguments":{"age":30}}}
                """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThatJson(response.body()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"required property 'name' not found"}}
                """);
        }
    }

    @Test
    protected void shouldRejectToolCallWithWrongType() throws Exception {
        startServer(
                b -> b.json(j -> j.schemaValidator(VALIDATOR)),
                s -> s.tools().register(validatedTool(), (context, request) -> ToolResult.text("ok")));

        try (var client = readyClient()) {
            var response = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"validated","arguments":{"name":123}}}
                """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThatJson(response.body()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"/name: integer found, string expected"}}
                """);
        }
    }

    @Test
    protected void shouldRejectPromptWithMissingRequiredField() throws Exception {
        startServer(it -> it.json(j -> j.schemaValidator(VALIDATOR)));

        server.prompts()
                .register(
                        PromptDescriptor.of(
                                "validated-prompt",
                                "A validated prompt",
                                null,
                                List.of(PromptArgument.of("name", null, "Your name", true)),
                                PROMPT_SCHEMA),
                        List.of(PromptMessage.of(Role.USER, TextContent.of("Hello {name}"))));

        try (var client = readyClient()) {
            var response = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"prompts/get","params":{"name":"validated-prompt","arguments":{}}}
                """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThatJson(response.body()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"required property 'name' not found"}}
                """);
        }
    }
}
