/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.e2e.mcp.EchoToolHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.BiConsumer;
import net.javacrumbs.jsonunit.assertj.JsonAssertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies a custom {@link SessionIdGenerator} derives the session id
 * from an incoming request header, and that the derived id is usable for follow-up requests.
 *
 * @author Konstantin Pavlov
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomSessionIdGeneratorTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    private TachyonServer server;
    private int port;

    @BeforeAll
    void beforeAll() {
        server = TachyonServer.builder()
                .session(s -> s.enabled()
                        .sessionIdGenerator((channelContext, request) ->
                                "tenant-" + request.headers().get(TENANT_HEADER)))
                .network(n -> n.host("localhost").port(0))
                .build();
        server.tools().registerAsync(EchoToolHandler.DESCRIPTOR, EchoToolHandler.FN);
        server.start();
        port = server.port();
    }

    @AfterAll
    void afterAll() {
        server.close();
    }

    @Test
    void shouldDeriveSessionIdFromRequestHeaderAndReuseIt() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var init = post(
                    client,
                    "acme",
                    null,
                    // language=JSON
                    """
                    {"jsonrpc":"2.0","id":1,"method":"initialize",
                     "params":{"protocolVersion":"2025-11-25","capabilities":{},
                               "clientInfo":{"name":"test","version":"1.0"}}}
                    """);

            assertThat(init.statusCode()).isEqualTo(200);
            assertThat(init.headers().firstValue("MCP-Session-Id")).hasValue("tenant-acme");

            // Activate the session, then reuse the derived id for a real request.
            post(client, null, "tenant-acme", """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);

            var toolsList = post(client, null, "tenant-acme", """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """);

            assertThat(toolsList.statusCode()).isEqualTo(200);
            assertThatJson(toolsList.body()).inPath("$.result.tools").isArray().isNotEmpty();
        }
    }

    @Test
    void shouldReturnInternalErrorWhenCustomGeneratorThrows() throws Exception {
        // SessionIdGenerator has no fallback by design (see its javadoc): a thrown exception
        // aborts session creation with an internal-error response, it does not fall back to
        // the default generator.
        initializeAndVerify(
                (channelContext, request) -> {
                    throw new IllegalStateException("boom");
                },
                33,
                this::assertInternalErrorResponse);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldReturnInternalErrorWhenGeneratedSessionIdIsNullOrBlank(@Nullable String generatedId) throws Exception {
        // SessionIdGenerator's javadoc: a null or blank return value aborts session creation
        // with an internal-error, same as a thrown exception.
        initializeAndVerify((channelContext, request) -> generatedId, 34, this::assertInternalErrorResponse);
    }

    private void initializeAndVerify(
            SessionIdGenerator<Object> generator, int requestId, BiConsumer<HttpResponse<String>, Integer> verifier)
            throws Exception {
        var handle = startServerWith(generator);
        try (handle;
                var client = HttpClient.newHttpClient()) {
            var init = post(
                    client,
                    handle.port(),
                    null,
                    null,
                    // language=JSON
                    """
                    {"jsonrpc":"2.0","id":%d,"method":"initialize",
                     "params":{"protocolVersion":"2025-11-25","capabilities":{},
                               "clientInfo":{"name":"test","version":"1.0"}}}
                    """.formatted(requestId));

            verifier.accept(init, requestId);
        }
    }

    private TachyonServer startServerWith(SessionIdGenerator<Object> generator) {
        var handle = TachyonServer.builder()
                .session(s -> s.enabled().sessionIdGenerator(generator))
                .network(n -> n.host("localhost").port(0))
                .build();
        handle.start();
        return handle;
    }

    private void assertInternalErrorResponse(HttpResponse<String> response, int requestId) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonAssertions.assertThatJson(response.body())
                .isEqualTo(
                        // language=JSON
                        """
                     {"jsonrpc":"2.0","id":%d,"error":{"code":-32603,"message":"Internal error"}}
                    """.formatted(requestId));
    }

    private HttpResponse<String> post(
            HttpClient client, @Nullable String tenantId, @Nullable String sessionId, String body) throws Exception {
        return post(client, port, tenantId, sessionId, body);
    }

    private HttpResponse<String> post(
            HttpClient client, int targetPort, @Nullable String tenantId, @Nullable String sessionId, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + targetPort + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (tenantId != null) {
            builder.header(TENANT_HEADER, tenantId);
        }
        if (sessionId != null) {
            builder.header("MCP-Session-Id", sessionId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
