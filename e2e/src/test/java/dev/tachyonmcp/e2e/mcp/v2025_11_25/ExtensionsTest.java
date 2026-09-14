/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ClientCapabilities;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeRequestParams;
import java.util.Map;
import java.util.Set;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class ExtensionsTest extends AbstractStatefulMcpE2eTest {

    private static final String TEST_EXT_ID = "com.example/test";
    private static final String INTERNAL_EXT_ID = "com.example/internal";
    private static final String NEGOTIATED_EXT_ID = "com.example/negotiated";

    @Test
    void serverAdvertisesExtensionInCapabilities() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension()));

        try (var client = createTestClient()) {
            // Send initialize with matching extension
            var initBody = buildInitializeJson(Map.of(TEST_EXT_ID, JsonNodeFactory.instance.objectNode()));
            var response = client.post(null, initBody);
            assertThatJson(response.body())
                    .inPath("$.result.capabilities.extensions")
                    // language=JSON
                    .isEqualTo("""
                            {"com.example/test": {"version": "1.0"}}
                            """);
        }
    }

    @Test
    void extensionAdvertisedWhenClientDoesNotDeclare() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension()));

        try (var client = createTestClient()) {
            var initBody = buildInitializeJson(Map.of());
            var response = client.post(null, initBody);
            assertThatJson(response.body())
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    // language=JSON
                    .isEqualTo("""
                            {
                              "result": {
                                "capabilities": {
                                  "extensions": {"com.example/test": {"version": "1.0"}}
                                }
                              }
                            }
                            """);
        }
    }

    @Test
    void extensionNotAdvertisedWhenAdvertiseModeIsNever() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension(), new NeverAdvertisedTestExtension()));

        try (var client = createTestClient()) {
            var initBody = buildInitializeJson(Map.of());
            var response = client.post(null, initBody);
            assertThatJson(response.body())
                    .inPath("$.result.capabilities.extensions")
                    // language=JSON
                    .isEqualTo("""
                    {"com.example/test": {"version": "1.0"}}
                    """);
        }
    }

    @Test
    void neverAdvertisedExtensionMethodStillWorksWhenNegotiated() throws Exception {
        startServer(it -> it.withExtensions(new NeverAdvertisedTestExtension()));

        try (var client = createTestClient()) {
            var initBody = buildInitializeJson(Map.of(INTERNAL_EXT_ID, JsonNodeFactory.instance.objectNode()));
            var response = client.post(null, initBody);
            var sessionId = response.headers().firstValue("MCP-Session-Id").orElseThrow();
            client.sendInitialized(sessionId);

            // language=JSON
            var call = """
                {"jsonrpc":"2.0","id":2,"method":"internal/hello","params":{"_meta":{"com.example/internal":{}}}}
                """;
            var callResp = client.sendRpc(sessionId, call);
            assertThat(callResp).isSuccess().hasId(2).hasResult("""
                    {"message":"Hi!"}
                """);
        }
    }

    @Test
    void negotiatedExtensionAdvertisedWhenClientDeclaresIt() throws Exception {
        startServer(it -> it.withExtensions(new NegotiatedTestExtension()));

        try (var client = createTestClient()) {
            var initBody = buildInitializeJson(Map.of(NEGOTIATED_EXT_ID, JsonNodeFactory.instance.objectNode()));
            var response = client.post(null, initBody);
            assertThatJson(response.body())
                    .inPath("$.result.capabilities.extensions")
                    // language=JSON
                    .isEqualTo("""
                            {"com.example/negotiated": {"version": "1.0"}}
                            """);
        }
    }

    @Test
    void negotiatedExtensionNotAdvertisedWhenClientDoesNotDeclareIt() throws Exception {
        startServer(it -> it.withExtensions(new NegotiatedTestExtension()));

        try (var client = createTestClient()) {
            var initBody = buildInitializeJson(Map.of());
            var response = client.post(null, initBody);
            assertThatJson(response.body())
                    .node("result.capabilities.extensions")
                    .isAbsent();
        }
    }

    @Test
    void extensionEnabledWhenClientDeclaresIt() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension()));

        try (var client = createTestClient()) {
            var initBody = buildInitializeJson(Map.of(TEST_EXT_ID, JsonNodeFactory.instance.objectNode()));
            var response = client.post(null, initBody);
            assertThatJson(response.body())
                    .inPath("$.result.capabilities.extensions")
                    // language=JSON
                    .isEqualTo("""
                            {"com.example/test": {"version": "1.0"}}
                            """);
        }
    }

    @Test
    void extensionMethodRequiresMetaEnvelope() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension()));

        try (var client = createTestClient()) {
            var initBody = buildInitializeJson(Map.of(TEST_EXT_ID, JsonNodeFactory.instance.objectNode()));
            var response = client.post(null, initBody);
            var sessionId = response.headers().firstValue("MCP-Session-Id").orElseThrow();
            client.sendInitialized(sessionId);

            // Call extension method WITHOUT meta envelope -> known method, invalid params
            // language=JSON
            var callWithoutMeta = """
                    {"jsonrpc":"2.0","id":2,"method":"test/ext-call","params":{}}
                    """;
            var resp1 = client.post(sessionId, callWithoutMeta);
            assertThat(resp1)
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessage("Missing required client capability: " + TEST_EXT_ID);

            // Call extension method WITH meta envelope -> should succeed
            // language=JSON
            var callWithMeta = """
                    {"jsonrpc":"2.0","id":3,"method":"test/ext-call","params":{"_meta":{"com.example/test":{}}}}
                    """;
            var resp2 = client.sendRpc(sessionId, callWithMeta);
            assertThat(resp2).isSuccess().hasId(3).hasResult("""
                {"status":"ok"}
                """);
        }
    }

    @Test
    void extensionToolInvisibleWhenNotNegotiated() throws Exception {
        startServer(it -> it.withExtensions(new TestExtensionWithTool()));

        try (var client = createTestClient()) {
            var response = client.post(null, buildInitializeJson(Map.of()));
            var sessionId = response.headers().firstValue("MCP-Session-Id").orElseThrow();
            client.sendInitialized(sessionId);

            var listResp = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """);
            assertThatJson(listResp.body()).inPath("$.result.tools").isEqualTo("[]");

            var callResp = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ext-tool","arguments":{}}}
                    """);
            assertThatJson(callResp.body()).isEqualTo("""
                    {
                      "jsonrpc": "2.0",
                      "id": 3,
                      "error": {
                        "code": -32602,
                        "message": "Unknown tool: ext-tool"
                      }
                    }
                    """);
        }
    }

    @Test
    void extensionToolVisibleAndCallableWhenNegotiated() throws Exception {
        startServer(it -> it.withExtensions(new TestExtensionWithTool()));

        try (var client = createTestClient()) {
            var response =
                    client.post(null, buildInitializeJson(Map.of(TEST_EXT_ID, JsonNodeFactory.instance.objectNode())));
            var sessionId = response.headers().firstValue("MCP-Session-Id").orElseThrow();
            client.sendInitialized(sessionId);

            var listResp = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """);
            assertThatJson(listResp.body())
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    .inPath("$.result.tools[0]")
                    // language=JSON
                    .isEqualTo("""
                            {"name": "ext-tool"}
                            """);

            var callResp = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ext-tool","arguments":{}}}
                    """);
            assertThatJson(callResp.body())
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    .inPath("$.result.content[0]")
                    // language=JSON
                    .isEqualTo("""
                            {"text": "ext-tool-result"}
                            """);
        }
    }

    private static String buildInitializeJson(Map<String, JsonNode> extensions) {
        var capsBuilder = ClientCapabilities.builder();
        if (!extensions.isEmpty()) {
            capsBuilder.extensions(extensions);
        }
        var caps = capsBuilder.build();
        var params = InitializeRequestParams.builder()
                .protocolVersion("2025-11-25")
                .capabilities(caps)
                .clientInfo(new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Implementation(
                        "1.0", null, null, "test-client", null, null))
                .build();
        var mapper = new tools.jackson.databind.ObjectMapper();
        try {
            var paramsJson = mapper.writeValueAsString(params);
            return """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":%s}
                    """.formatted(paramsJson);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class TestExtension implements ServerExtension {

        @Override
        public String extensionId() {
            return TEST_EXT_ID;
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.ALWAYS;
        }

        @Override
        public Set<String> methods() {
            return Set.of("test/ext-call");
        }

        @Override
        public ExtensionSettings serverSettings() {
            return ExtensionSettings.of(Map.of("version", "1.0"));
        }

        @Override
        public void bootstrap(ExtensionContext context) {
            context.registerHandler("test/ext-call", (interaction, params) -> Map.of("status", "ok"));
        }
    }

    private static class NeverAdvertisedTestExtension implements ServerExtension {

        @Override
        public String extensionId() {
            return INTERNAL_EXT_ID;
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.NEVER;
        }

        @Override
        public Set<String> methods() {
            return Set.of("internal/hello");
        }

        @Override
        public void bootstrap(ExtensionContext context) {
            context.registerHandler("internal/hello", (interaction, params) -> Map.of("message", "Hi!"));
        }
    }

    private static class NegotiatedTestExtension implements ServerExtension {

        @Override
        public String extensionId() {
            return NEGOTIATED_EXT_ID;
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.NEGOTIATED;
        }

        @Override
        public ExtensionSettings serverSettings() {
            return ExtensionSettings.of(Map.of("version", "1.0"));
        }
    }

    private static class TestExtensionWithTool implements ServerExtension {

        @Override
        public String extensionId() {
            return TEST_EXT_ID;
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.ALWAYS;
        }

        @Override
        public void bootstrap(ExtensionContext server) {
            server.tools()
                    .register(
                            ToolDescriptor.builder()
                                    .name("ext-tool")
                                    .description("Extension-owned tool")
                                    .extensionId(TEST_EXT_ID)
                                    .build(),
                            (context, request) -> ToolResult.text("ext-tool-result"));
        }
    }
}
