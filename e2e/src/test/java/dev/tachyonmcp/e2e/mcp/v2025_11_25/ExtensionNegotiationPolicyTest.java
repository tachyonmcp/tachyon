/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SEP-2133 extension negotiation policy under MCP 2025-11-25: client declaration comes from
 * {@code initialize.params.capabilities.extensions} and persists for the session.
 */
class ExtensionNegotiationPolicyTest extends AbstractStatefulMcpE2eTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REQUIRED_ID = "com.example/required";
    private static final String DEFAULTED_ID = "com.example/defaulted";
    private static final String OPTIONAL_ID = "com.example/optional";

    private static final String UNDECLARED_REQUIRED = "\"capabilities\":{\"extensions\":{}},";
    private static final String OTHER_EXTENSION_ONLY = "\"capabilities\":{\"extensions\":{\"com.example/other\":{}}},";
    private static final String NO_EXTENSIONS_MAP = "\"capabilities\":{},";
    private static final String NO_CAPABILITIES = "";

    private RecordingExtension required;
    private RecordingExtension defaulted;
    private RecordingExtension optional;

    @BeforeEach
    void startServerWithExtensions() {
        required = new RecordingExtension(REQUIRED_ID, "required/call", ExtensionNegotiation.REQUIRED);
        defaulted = new RecordingExtension(DEFAULTED_ID, "defaulted/call", null);
        optional = new RecordingExtension(OPTIONAL_ID, "optional/call", ExtensionNegotiation.OPTIONAL);
        startServer(it -> it.withExtensions(required, defaulted, optional));
    }

    @Test
    void requiredExtensionDispatchesWhenDeclared() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client, declaring(REQUIRED_ID));

            var response = call(client, sessionId, 2, "required/call");

            assertThat(response).isSuccess().hasId(2).hasResult("""
                    {"handled":"required/call"}
                    """);
            assertThat(required.declaredSeenByHandler).containsExactly(true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {UNDECLARED_REQUIRED, OTHER_EXTENSION_ONLY, NO_EXTENSIONS_MAP, NO_CAPABILITIES})
    void requiredExtensionRejectsUndeclaredClientWithoutInvokingHandler(String capabilities) throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client, capabilities);

            var response = call(client, sessionId, 2, "required/call");

            assertThat(response).isJsonRpcError().hasId(2).hasError(missingExtension(REQUIRED_ID));
            assertThat(required.declaredSeenByHandler).isEmpty();
        }
    }

    @Test
    void negotiationIsRequiredByDefault() throws Exception {
        assertThat(defaulted.negotiation()).isEqualTo(ExtensionNegotiation.REQUIRED);
        try (var client = createTestClient()) {
            var sessionId = openSession(client, declaring(REQUIRED_ID));

            var response = call(client, sessionId, 2, "defaulted/call");

            assertThat(response).isJsonRpcError().hasId(2).hasError(missingExtension(DEFAULTED_ID));
            assertThat(defaulted.declaredSeenByHandler).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {UNDECLARED_REQUIRED, OTHER_EXTENSION_ONLY, NO_EXTENSIONS_MAP, NO_CAPABILITIES})
    void optionalExtensionDispatchesUndeclaredClientWithoutSynthesizingDeclaration(String capabilities)
            throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client, capabilities);

            // optional extension also requires a _meta envelope, which OPTIONAL skips
            var response = call(client, sessionId, 2, "optional/call");

            assertThat(response).isSuccess().hasId(2).hasResult("""
                    {"handled":"optional/call"}
                    """);
            assertThat(optional.declaredSeenByHandler).containsExactly(false);
            assertThat(optional.connectionInits).isEmpty();
        }
    }

    @Test
    void optionalExtensionDispatchesDeclaredClient() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client, declaring(OPTIONAL_ID));

            var response = call(client, sessionId, 2, "optional/call");

            assertThat(response).isSuccess().hasId(2).hasResult("""
                    {"handled":"optional/call"}
                    """);
            assertThat(optional.declaredSeenByHandler).containsExactly(true);
            assertThat(optional.connectionInits).containsExactly(OPTIONAL_ID);
        }
    }

    @Test
    void policiesApplyPerExtensionWhenClientDeclaresOnlyOne() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client, declaring(REQUIRED_ID));

            assertThat(call(client, sessionId, 2, "required/call")).isSuccess().hasId(2);
            assertThat(call(client, sessionId, 3, "defaulted/call"))
                    .isJsonRpcError()
                    .hasId(3)
                    .hasError(missingExtension(DEFAULTED_ID));
            assertThat(call(client, sessionId, 4, "optional/call")).isSuccess().hasId(4);

            assertThat(required.declaredSeenByHandler).containsExactly(true);
            assertThat(defaulted.declaredSeenByHandler).isEmpty();
            assertThat(optional.declaredSeenByHandler).containsExactly(false);
        }
    }

    @Test
    void unownedMethodsStayMethodNotFound() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client, NO_CAPABILITIES);

            assertThat(call(client, sessionId, 2, "required/unknown"))
                    .isJsonRpcError()
                    .hasId(2)
                    .hasHttpStatusCode(200)
                    .isMethodNotFound();
            assertThat(call(client, sessionId, 3, "optional/unknown"))
                    .isJsonRpcError()
                    .hasId(3)
                    .hasHttpStatusCode(200)
                    .isMethodNotFound();
            assertThat(call(client, sessionId, 4, "skills/list"))
                    .isJsonRpcError()
                    .hasId(4)
                    .hasHttpStatusCode(200)
                    .isMethodNotFound();
        }
    }

    @Test
    void coreMethodsIgnoreExtensionPolicy() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client, NO_CAPABILITIES);

            var response = call(client, sessionId, 2, "tools/list");

            assertThat(response).isSuccess().hasId(2).hasResult("""
                    {"tools":[]}
                    """);
        }
    }

    private static String declaring(String extensionId) {
        return "\"capabilities\":{\"extensions\":{\"%s\":{}}},".formatted(extensionId);
    }

    private static String openSession(Mcp20251125Client client, String capabilitiesField) throws Exception {
        var response = client.post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{%s\
                "protocolVersion":"2025-11-25","clientInfo":{"name":"test","version":"1.0"}}}
                """.formatted(capabilitiesField));
        assertThat(response).isSuccess().hasId(1);
        var sessionId = response.headers().firstValue("MCP-Session-Id").orElseThrow();
        client.sendInitialized(sessionId);
        return sessionId;
    }

    private static HttpResponse<String> call(Mcp20251125Client client, String sessionId, int id, String method)
            throws Exception {
        return client.sendRpc(sessionId, """
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":{}}
                """.formatted(id, method));
    }

    private static JsonNode missingExtension(String extensionId) {
        // 2025-11-25 maps MISSING_REQUIRED_CLIENT_CAPABILITY to -32003
        return MAPPER.readTree("""
                {
                  "code": -32003,
                  "message": "Requires the '%1$s' extension",
                  "data": {"requiredCapabilities": {"extensions": {"%1$s": {}}}}
                }
                """.formatted(extensionId));
    }

    private static final class RecordingExtension implements ServerExtension {

        final CopyOnWriteArrayList<Boolean> declaredSeenByHandler = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> connectionInits = new CopyOnWriteArrayList<>();
        private final String id;
        private final String method;
        private final @Nullable ExtensionNegotiation negotiation;

        RecordingExtension(String id, String method, @Nullable ExtensionNegotiation negotiation) {
            this.id = id;
            this.method = method;
            this.negotiation = negotiation;
        }

        @Override
        public String extensionId() {
            return id;
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.ALWAYS;
        }

        @Override
        public Set<String> methods() {
            return Set.of(method);
        }

        @Override
        public ExtensionNegotiation negotiation() {
            return negotiation != null ? negotiation : ServerExtension.super.negotiation();
        }

        @Override
        public void onConnectionInit(InteractionContext context, ExtensionSettings clientSettings) {
            connectionInits.add(id);
        }

        @Override
        public void bootstrap(ExtensionContext context) {
            context.registerHandler(method, (interaction, params) -> {
                declaredSeenByHandler.add(interaction.isExtensionEnabled(id));
                return Map.of("handled", method);
            });
        }
    }
}
