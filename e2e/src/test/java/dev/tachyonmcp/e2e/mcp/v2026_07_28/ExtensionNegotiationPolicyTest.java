/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
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
 * SEP-2133 extension negotiation policy under MCP 2026-07-28: client declaration is read from each
 * request's {@code _meta."io.modelcontextprotocol/clientCapabilities".extensions} and never carried
 * over to later requests.
 */
class ExtensionNegotiationPolicyTest extends AbstractStatelessMcpE2eTest<McpClient> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REQUIRED_ID = "com.example/required";
    private static final String DEFAULTED_ID = "com.example/defaulted";
    private static final String OPTIONAL_ID = "com.example/optional";

    private static final String UNDECLARED_REQUIRED = "{\"extensions\":{}}";
    private static final String OTHER_EXTENSION_ONLY = "{\"extensions\":{\"com.example/other\":{}}}";
    private static final String NO_EXTENSIONS_MAP = "{}";

    private RecordingExtension required;
    private RecordingExtension defaulted;
    private RecordingExtension optional;

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @BeforeEach
    void startServerWithExtensions() {
        required = new RecordingExtension(REQUIRED_ID, "required/call", ExtensionNegotiation.REQUIRED);
        defaulted = new RecordingExtension(DEFAULTED_ID, "defaulted/call", null);
        optional = new RecordingExtension(OPTIONAL_ID, "optional/call", ExtensionNegotiation.OPTIONAL);
        startServer(it -> it.withExtensions(required, defaulted, optional));
    }

    @Test
    void requiredExtensionDispatchesWhenDeclared() throws Exception {
        try (var client = createModernTestClient()) {
            var response = call(client, 1, "required/call", declaring(REQUIRED_ID));

            assertThat(response).isSuccess().hasId(1).hasResult("""
                    {"handled":"required/call"}
                    """);
            assertThat(required.declaredSeenByHandler).containsExactly(true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {UNDECLARED_REQUIRED, OTHER_EXTENSION_ONLY, NO_EXTENSIONS_MAP})
    void requiredExtensionRejectsUndeclaredRequestWithoutInvokingHandler(String capabilities) throws Exception {
        try (var client = createModernTestClient()) {
            var response = call(client, 1, "required/call", capabilities);

            assertThat(response)
                    .isJsonRpcError()
                    .hasHttpStatusCode(400)
                    .hasId(1)
                    .hasError(missingExtension(REQUIRED_ID));
            assertThat(required.declaredSeenByHandler).isEmpty();
        }
    }

    @Test
    void negotiationIsRequiredByDefault() throws Exception {
        assertThat(defaulted.negotiation()).isEqualTo(ExtensionNegotiation.REQUIRED);
        try (var client = createModernTestClient()) {
            var response = call(client, 1, "defaulted/call", declaring(REQUIRED_ID));

            assertThat(response)
                    .isJsonRpcError()
                    .hasHttpStatusCode(400)
                    .hasId(1)
                    .hasError(missingExtension(DEFAULTED_ID));
            assertThat(defaulted.declaredSeenByHandler).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {UNDECLARED_REQUIRED, OTHER_EXTENSION_ONLY, NO_EXTENSIONS_MAP})
    void optionalExtensionDispatchesUndeclaredRequestWithoutSynthesizingDeclaration(String capabilities)
            throws Exception {
        try (var client = createModernTestClient()) {
            var response = call(client, 1, "optional/call", capabilities);

            assertThat(response).isSuccess().hasId(1).hasResult("""
                    {"handled":"optional/call"}
                    """);
            assertThat(optional.declaredSeenByHandler).containsExactly(false);
            assertThat(optional.connectionInits).isEmpty();
        }
    }

    @Test
    void optionalExtensionDispatchesDeclaredRequest() throws Exception {
        try (var client = createModernTestClient()) {
            var response = call(client, 1, "optional/call", declaring(OPTIONAL_ID));

            assertThat(response).isSuccess().hasId(1).hasResult("""
                    {"handled":"optional/call"}
                    """);
            assertThat(optional.declaredSeenByHandler).containsExactly(true);
            assertThat(optional.connectionInits).containsExactly(OPTIONAL_ID);
        }
    }

    @Test
    void declarationDoesNotLeakIntoLaterRequest() throws Exception {
        try (var client = createModernTestClient()) {
            assertThat(call(client, 1, "required/call", declaring(REQUIRED_ID)))
                    .isSuccess()
                    .hasId(1);
            assertThat(call(client, 2, "required/call", NO_EXTENSIONS_MAP))
                    .isJsonRpcError()
                    .hasHttpStatusCode(400)
                    .hasId(2)
                    .hasError(missingExtension(REQUIRED_ID));

            assertThat(required.declaredSeenByHandler).containsExactly(true);
        }
    }

    @Test
    void earlierRejectionDoesNotBlockLaterDeclaredRequest() throws Exception {
        try (var client = createModernTestClient()) {
            assertThat(call(client, 1, "required/call", NO_EXTENSIONS_MAP))
                    .isJsonRpcError()
                    .hasHttpStatusCode(400)
                    .hasId(1)
                    .hasError(missingExtension(REQUIRED_ID));
            assertThat(call(client, 2, "required/call", declaring(REQUIRED_ID)))
                    .isSuccess()
                    .hasId(2);

            assertThat(required.declaredSeenByHandler).containsExactly(true);
        }
    }

    @Test
    void policiesApplyPerExtensionWhenRequestDeclaresOnlyOne() throws Exception {
        try (var client = createModernTestClient()) {
            var declared = declaring(REQUIRED_ID);

            assertThat(call(client, 1, "required/call", declared)).isSuccess().hasId(1);
            assertThat(call(client, 2, "defaulted/call", declared))
                    .isJsonRpcError()
                    .hasHttpStatusCode(400)
                    .hasId(2)
                    .hasError(missingExtension(DEFAULTED_ID));
            assertThat(call(client, 3, "optional/call", declared)).isSuccess().hasId(3);

            assertThat(required.declaredSeenByHandler).containsExactly(true);
            assertThat(defaulted.declaredSeenByHandler).isEmpty();
            assertThat(optional.declaredSeenByHandler).containsExactly(false);
        }
    }

    @Test
    void unownedMethodsStayMethodNotFound() throws Exception {
        try (var client = createModernTestClient()) {
            assertThat(call(client, 1, "required/unknown", NO_EXTENSIONS_MAP))
                    .isJsonRpcError()
                    .hasId(1)
                    .hasHttpStatusCode(404)
                    .isMethodNotFound();
            assertThat(call(client, 2, "optional/unknown", NO_EXTENSIONS_MAP))
                    .isJsonRpcError()
                    .hasId(2)
                    .hasHttpStatusCode(404)
                    .isMethodNotFound();
            assertThat(call(client, 3, "skills/list", NO_EXTENSIONS_MAP))
                    .isJsonRpcError()
                    .hasId(3)
                    .hasHttpStatusCode(404)
                    .isMethodNotFound();
        }
    }

    @Test
    void coreMethodsIgnoreExtensionPolicy() throws Exception {
        try (var client = createModernTestClient()) {
            var response = call(client, 1, "tools/list", NO_EXTENSIONS_MAP);

            assertThat(response).isSuccess().hasId(1).hasResultType("complete");
        }
    }

    @Test
    void bothPoliciesAreAdvertised() throws Exception {
        try (var client = createModernTestClient()) {
            client.discover().isSuccess().hasCapabilities("""
                    {
                      "extensions": {
                        "com.example/required": {},
                        "com.example/defaulted": {},
                        "com.example/optional": {}
                      }
                    }
                    """);
        }
    }

    private static String declaring(String extensionId) {
        return "{\"extensions\":{\"%s\":{}}}".formatted(extensionId);
    }

    private static HttpResponse<String> call(Mcp20260728Client client, int id, String method, String capabilities)
            throws Exception {
        return client.post("""
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":{"_meta":\
                {"io.modelcontextprotocol/clientCapabilities":%s}}}
                """.formatted(id, method, capabilities));
    }

    private static JsonNode missingExtension(String extensionId) {
        return MAPPER.readTree("""
                {
                  "code": -32021,
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
