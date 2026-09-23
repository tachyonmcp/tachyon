/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.McpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * SEP-2133 extension behaviour shared by both MCP revisions: capability advertisement per
 * {@link AdvertiseMode}, raw method dispatch, extension-owned tool visibility, and raw handler
 * exception mapping. Subclasses in {@code v2025_11_25}/{@code v2026_07_28} supply how a client
 * declares extensions ({@code initialize} within a session vs. per-request {@code _meta}), where
 * capabilities are advertised ({@code initialize} vs. {@code server/discover}), and the HTTP status
 * each revision ties to invalid params.
 */
public abstract class AbstractExtensionsTest<C extends McpClient> extends AbstractMcpE2eTest<C> {

    protected static final String TEST_EXT_ID = "com.example/test";
    protected static final String INTERNAL_EXT_ID = "com.example/internal";
    protected static final String NEGOTIATED_EXT_ID = "com.example/negotiated";

    /** Sends this revision's capability-advertising request declaring {@code extensions}. */
    protected abstract HttpResponse<String> advertise(Map<String, JsonNode> extensions) throws Exception;

    /** Returns a client whose subsequent requests carry the {@code extensions} declaration. */
    protected abstract C negotiatedClient(Map<String, JsonNode> extensions) throws Exception;

    /** POSTs {@code body} as a request of {@code client}'s negotiated context (session, if any). */
    protected abstract HttpResponse<String> send(C client, String body) throws Exception;

    /** Returns the HTTP status this protocol revision ties to an {@code -32602} invalid-params error. */
    protected abstract int invalidParamsHttpStatus();

    private static Map<String, JsonNode> declaring(String extensionId) {
        return Map.of(extensionId, JsonNodeFactory.instance.objectNode());
    }

    @Test
    void alwaysExtensionAdvertisedWhenClientDeclaresIt() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension()));

        assertThatJson(advertise(declaring(TEST_EXT_ID)).body())
                .inPath("$.result.capabilities.extensions")
                // language=JSON
                .isEqualTo("""
                        {"com.example/test": {"version": "1.0"}}
                        """);
    }

    @Test
    void alwaysExtensionAdvertisedWhenClientDoesNotDeclareIt() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension()));

        assertThatJson(advertise(Map.of()).body())
                .inPath("$.result.capabilities.extensions")
                // language=JSON
                .isEqualTo("""
                        {"com.example/test": {"version": "1.0"}}
                        """);
    }

    @Test
    void extensionNotAdvertisedWhenAdvertiseModeIsNever() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension(), new NeverAdvertisedTestExtension()));

        assertThatJson(advertise(declaring(INTERNAL_EXT_ID)).body())
                .inPath("$.result.capabilities.extensions")
                // language=JSON
                .isEqualTo("""
                        {"com.example/test": {"version": "1.0"}}
                        """);
    }

    @Test
    void neverAdvertisedExtensionMethodStillWorksWhenNegotiated() throws Exception {
        startServer(it -> it.withExtensions(new NeverAdvertisedTestExtension()));

        try (var client = negotiatedClient(declaring(INTERNAL_EXT_ID))) {
            assertThat(send(client, """
                    {"jsonrpc":"2.0","id":2,"method":"internal/hello","params":{}}
                    """)).isSuccess().hasId(2).hasResult("""
                            {"message":"Hi!"}
                            """);
        }
    }

    @Test
    void negotiatedExtensionAdvertisedWhenClientDeclaresIt() throws Exception {
        startServer(it -> it.withExtensions(new NegotiatedTestExtension()));

        assertThatJson(advertise(declaring(NEGOTIATED_EXT_ID)).body())
                .inPath("$.result.capabilities.extensions")
                // language=JSON
                .isEqualTo("""
                        {"com.example/negotiated": {"version": "1.0"}}
                        """);
    }

    @Test
    void negotiatedExtensionNotAdvertisedWhenClientDoesNotDeclareIt() throws Exception {
        startServer(it -> it.withExtensions(new NegotiatedTestExtension()));

        assertThatJson(advertise(Map.of()).body())
                .node("result.capabilities.extensions")
                .isAbsent();
    }

    @Test
    void declaredExtensionMethodDispatchesWithoutMetaEnvelope() throws Exception {
        startServer(it -> it.withExtensions(new TestExtension()));

        try (var client = negotiatedClient(declaring(TEST_EXT_ID))) {
            // SEP-2133 negotiates via capabilities only; no per-call _meta envelope is required
            assertThat(send(client, """
                    {"jsonrpc":"2.0","id":2,"method":"test/ext-call","params":{}}
                    """)).isSuccess().hasId(2).hasResult("""
                            {"status":"ok"}
                            """);

            assertThat(send(client, """
                    {"jsonrpc":"2.0","id":3,"method":"test/ext-call","params":{"_meta":{"com.example/test":{}}}}
                    """)).isSuccess().hasId(3).hasResult("""
                            {"status":"ok"}
                            """);
        }
    }

    @Test
    void extensionToolInvisibleWhenNotNegotiated() throws Exception {
        startServer(it -> it.withExtensions(new TestExtensionWithTool()));

        try (var client = negotiatedClient(Map.of())) {
            assertThatJson(send(client, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """).body()).inPath("$.result.tools").isEqualTo("[]");

            assertThat(send(client, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ext-tool","arguments":{}}}
                    """))
                    .isJsonRpcError()
                    .hasId(3)
                    .hasHttpStatusCode(invalidParamsHttpStatus())
                    .hasErrorCode(-32602)
                    .hasErrorMessage("Unknown tool: ext-tool");
        }
    }

    @Test
    void extensionToolVisibleAndCallableWhenNegotiated() throws Exception {
        startServer(it -> it.withExtensions(new TestExtensionWithTool()));

        try (var client = negotiatedClient(declaring(TEST_EXT_ID))) {
            assertThatJson(send(client, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """).body())
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    .inPath("$.result.tools")
                    // language=JSON
                    .isEqualTo("""
                            [{"name": "ext-tool"}]
                            """);

            assertThat(send(client, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ext-tool","arguments":{}}}
                    """)).isSuccess().hasId(3).hasTextContent("ext-tool-result");
        }
    }

    @Test
    void invalidArgumentExceptionMapsToInvalidParamsWithMessage() throws Exception {
        startServer(it -> it.withExtensions(new ThrowingTestExtension()));

        try (var client = negotiatedClient(Map.of())) {
            assertThat(send(client, """
                    {"jsonrpc":"2.0","id":2,"method":"throwing/invalid-argument","params":{}}
                    """))
                    .isJsonRpcError()
                    .hasId(2)
                    .hasHttpStatusCode(invalidParamsHttpStatus())
                    .hasErrorCode(-32602)
                    .hasErrorMessage("invalid argument 'city': must not be blank");
        }
    }

    @Test
    void illegalArgumentExceptionMapsToRedactedInvalidParams() throws Exception {
        startServer(it -> it.withExtensions(new ThrowingTestExtension()));

        try (var client = negotiatedClient(Map.of())) {
            var response = send(client, """
                    {"jsonrpc":"2.0","id":2,"method":"throwing/illegal-argument","params":{}}
                    """);

            assertThat(response)
                    .isJsonRpcError()
                    .hasId(2)
                    .hasHttpStatusCode(invalidParamsHttpStatus())
                    .hasErrorCode(-32602)
                    .hasErrorMessage("Invalid params");
            assertThat(response.body()).doesNotContain("sensitive library detail");
        }
    }

    @Test
    void otherExceptionMapsToRedactedInternalError() throws Exception {
        startServer(it -> it.withExtensions(new ThrowingTestExtension()));

        try (var client = negotiatedClient(Map.of())) {
            var response = send(client, """
                    {"jsonrpc":"2.0","id":2,"method":"throwing/illegal-state","params":{}}
                    """);

            assertThat(response)
                    .isJsonRpcError()
                    .hasId(2)
                    .hasHttpStatusCode(200)
                    .hasErrorCode(-32603)
                    .hasErrorMessage("Internal error");
            assertThat(response.body()).doesNotContain("sensitive state detail");
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

    private static class ThrowingTestExtension implements ServerExtension {

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
            return Set.of("throwing/invalid-argument", "throwing/illegal-argument", "throwing/illegal-state");
        }

        @Override
        public void bootstrap(ExtensionContext context) {
            context.registerHandler("throwing/invalid-argument", (interaction, params) -> {
                throw new InvalidArgumentException("city", "must not be blank");
            });
            context.registerHandler("throwing/illegal-argument", (interaction, params) -> {
                throw new IllegalArgumentException("sensitive library detail");
            });
            context.registerHandler("throwing/illegal-state", (interaction, params) -> {
                throw new IllegalStateException("sensitive state detail");
            });
        }
    }
}
