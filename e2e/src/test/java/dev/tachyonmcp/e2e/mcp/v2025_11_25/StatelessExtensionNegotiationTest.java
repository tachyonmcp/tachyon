/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SEP-2133 negotiation on a stateless server under MCP 2025-11-25: the {@code initialize}
 * declaration belongs only to the initialize request, including when later requests reuse
 * the same TCP connection. The default policy falls back instead of rejecting; an explicit
 * {@code REQUIRED} policy keeps rejecting.
 */
class StatelessExtensionNegotiationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULTED_ID = "com.example/defaulted";
    private static final String REQUIRED_ID = "com.example/required";

    private final AtomicInteger connections = new AtomicInteger();
    private RecordingExtension defaulted;
    private RecordingExtension required;
    private TachyonServer server;

    @BeforeEach
    void startStatelessServer() {
        defaulted = new RecordingExtension(DEFAULTED_ID, "defaulted/call", null);
        required = new RecordingExtension(REQUIRED_ID, "required/call", ExtensionNegotiation.REQUIRED);
        server = TachyonServer.builder()
                .network(n -> n.host("localhost").port(0))
                .pipelineCustomizer(p -> connections.incrementAndGet())
                .withExtensions(defaulted, required)
                .build();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void defaultPolicyFallsBackRegardlessOfConnectionReuse(boolean reuseConnection) throws Exception {
        try (var initializer = new Mcp20251125Client(server.port());
                var client = new Mcp20251125Client(server.port())) {
            var initialize = initializeDeclaringBoth(initializer);

            var first = call(reuseConnection ? initializer : client, 2, "defaulted/call");
            var second = call(reuseConnection ? initializer : client, 3, "defaulted/call");

            assertThat(initialize.headers().firstValue("MCP-Session-Id")).isEmpty();
            assertThat(first).isSuccess().hasId(2).hasResult("""
                    {"handled":"defaulted/call"}
                    """);
            assertThat(second).isSuccess().hasId(3);
            assertThat(defaulted.connectionInits).containsExactly(DEFAULTED_ID);
            assertThat(defaulted.declaredSeenByHandler).containsExactly(false, false);
            assertThat(connections).hasValue(reuseConnection ? 1 : 2);
            assertThat(defaulted.initializationContexts)
                    .allSatisfy(context ->
                            assertThat(context.isExtensionEnabled(DEFAULTED_ID)).isTrue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requiredPolicyRejectsRegardlessOfConnectionReuse(boolean reuseConnection) throws Exception {
        try (var initializer = new Mcp20251125Client(server.port());
                var client = new Mcp20251125Client(server.port())) {
            initializeDeclaringBoth(initializer);

            var response = call(reuseConnection ? initializer : client, 2, "required/call");

            assertThat(response).isJsonRpcError().hasId(2).hasError(MAPPER.readTree("""
                    {
                      "code": -32003,
                      "message": "Requires the '%1$s' extension",
                      "data": {"requiredCapabilities": {"extensions": {"%1$s": {}}}}
                    }
                    """.formatted(REQUIRED_ID)));
            assertThat(required.connectionInits).containsExactly(REQUIRED_ID);
            assertThat(required.declaredSeenByHandler).isEmpty();
            assertThat(connections).hasValue(reuseConnection ? 1 : 2);
        }
    }

    @Test
    void overlappingRequestsOnOneConnectionKeepSeparateCapabilities() throws Exception {
        defaulted.blockInitialization = true;
        try (final var socket = new Socket("localhost", server.port())) {
            socket.setSoTimeout(10000);
            final var reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            send(socket, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                    "capabilities":{"extensions":{"com.example/defaulted":{}}},
                    "protocolVersion":"2025-11-25","clientInfo":{"name":"test","version":"1.0"}}}
                    """);
            assertThat(defaulted.initializationStarted.await(10, TimeUnit.SECONDS))
                    .isTrue();
            send(socket, """
                    {"jsonrpc":"2.0","id":2,"method":"defaulted/call","params":{}}
                    """);
            await().during(Duration.ofMillis(300))
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(defaulted.declaredSeenByHandler)
                            .as("a pipelined request waits for the response ahead of it (RFC 9112 §9.3.2)")
                            .isEmpty());
            defaulted.finishInitialization.countDown();

            assertThat(readResponse(reader)).isSuccess().hasId(1);
            assertThat(readResponse(reader)).isSuccess().hasId(2).hasResult("""
                    {"handled":"defaulted/call"}
                    """);
            assertThat(defaulted.declaredSeenByHandler)
                    .as("a stateless request does not inherit the connection's earlier initialize")
                    .containsExactly(false);
            assertThat(defaulted.initializationContexts)
                    .singleElement()
                    .satisfies(context ->
                            assertThat(context.isExtensionEnabled(DEFAULTED_ID)).isTrue());
            assertThat(connections).hasValue(1);
        } finally {
            defaulted.finishInitialization.countDown();
        }
    }

    private static void send(Socket socket, String body) throws Exception {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream()
                .write(("POST /mcp HTTP/1.1\r\nHost: localhost\r\n"
                                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                                + "MCP-Protocol-Version: 2025-11-25\r\nContent-Length: " + bytes.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static JsonNode readResponse(BufferedReader reader) throws Exception {
        assertThat(reader.readLine()).startsWith("HTTP/1.1 200");
        int length = -1;
        for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
            assertThat(line.toLowerCase(Locale.ROOT)).doesNotStartWith("mcp-session-id:");
        }
        assertThat(length).isNotNegative();
        final var body = new char[length];
        int offset = 0;
        while (offset < length) {
            final var read = reader.read(body, offset, length - offset);
            assertThat(read).isPositive();
            offset += read;
        }
        return MAPPER.readTree(new String(body));
    }

    private static HttpResponse<String> initializeDeclaringBoth(Mcp20251125Client client) throws Exception {
        var response = client.post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
                "capabilities":{"extensions":{"%s":{},"%s":{}}},\
                "protocolVersion":"2025-11-25","clientInfo":{"name":"test","version":"1.0"}}}
                """.formatted(DEFAULTED_ID, REQUIRED_ID));
        assertThat(response).isSuccess().hasId(1);
        client.sendInitialized(null);
        return response;
    }

    private static HttpResponse<String> call(Mcp20251125Client client, int id, String method) throws Exception {
        return client.sendRpc("""
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":{}}
                """.formatted(id, method));
    }

    private static final class RecordingExtension implements ServerExtension {

        final CountDownLatch initializationStarted = new CountDownLatch(1);
        final CountDownLatch finishInitialization = new CountDownLatch(1);
        volatile boolean blockInitialization;
        final CopyOnWriteArrayList<Boolean> declaredSeenByHandler = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<InteractionContext> initializationContexts = new CopyOnWriteArrayList<>();
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
            initializationContexts.add(context);
            if (blockInitialization) {
                initializationStarted.countDown();
                try {
                    assertThat(finishInitialization.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
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
