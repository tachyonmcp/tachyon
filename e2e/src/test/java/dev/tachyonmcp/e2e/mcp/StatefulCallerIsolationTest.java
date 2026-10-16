/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.core.server.security.Owner;
import dev.tachyonmcp.e2e.mcp.v2025_11_25.AbstractStatefulMcpE2eTest;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * On a stateful 2025-11-25 connection one interaction context serves every request, so it is the
 * place where a caller would leak from one request to the next. It holds none: each request carries
 * its own caller. Behind a provider that answers {@code anonymous} when there is no credential, a
 * stale identity would show up as a session owned by, or resolved for, the wrong caller.
 */
class StatefulCallerIsolationTest extends AbstractStatefulMcpE2eTest {

    @BeforeEach
    void startServerThatKnowsThreeKindsOfCaller() {
        final var tokens = new TestBearerTokens();
        startServer(
                b -> b.capabilities(c -> c.tools())
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll()).authenticationProvider(request -> {
                            final var authorization = request.headers("Authorization");
                            if (authorization.isEmpty()) {
                                return SecurityContext.anonymous();
                            }
                            return switch (authorization.getFirst()) {
                                case "Bearer alice" ->
                                    SecurityContext.authenticated(() -> "alice", Set.of("tools:read"));
                                case "Bearer bob" -> SecurityContext.authenticated(() -> "bob", Set.of());
                                default -> throw AuthenticationException.bearerInvalidToken("Unknown token");
                            };
                        })),
                tokens::registerWhoAmI);
    }

    @Test
    void initializesInOneWriteCreateSessionsOwnedByTheirOwnCaller() throws Exception {
        final var responses = pipelined(initialize("alice"), initialize(null), initialize("bob"));

        assertThat(responses).hasSize(3);
        assertThat(owner(responses.get(0))).isEqualTo(new Owner.Named("alice"));
        assertThat(owner(responses.get(1))).isEqualTo(Owner.anonymous());
        assertThat(owner(responses.get(2))).isEqualTo(new Owner.Named("bob"));
    }

    @Test
    void aGetOrPostWithoutCredentialsCannotResolveAsTheCallerBeforeIt() throws Exception {
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            final var created = exchange(socket, initialize("alice"));
            final var sessionId = created.header("mcp-session-id");
            assertThat(sessionId).isNotNull();

            final var get = exchange(
                    socket,
                    "GET /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\nAccept: text/event-stream\r\n"
                            + "MCP-Protocol-Version: 2025-11-25\r\nMCP-Session-Id: " + sessionId + "\r\n\r\n");

            assertThat(get.status())
                    .as("a GET with no token must not resolve alice's session as alice")
                    .isEqualTo(404);
            assertThat(get.header("content-type"))
                    .as("no event stream was opened")
                    .doesNotContain("event-stream");
        }
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            final var created = exchange(socket, initialize("alice"));
            final var sessionId = created.header("mcp-session-id");

            final var post = exchange(socket, call("whoami", null, sessionId));

            assertThat(post.status())
                    .as("a POST with no token must not use alice's session as alice")
                    .isEqualTo(404);
        }
    }

    private String initialize(@Nullable String token) {
        final var body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""";
        return post(body, token, null);
    }

    private String call(String tool, @Nullable String token, @Nullable String sessionId) {
        final var body = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{}}}""".formatted(tool);
        return post(body, token, sessionId);
    }

    private String post(String body, @Nullable String token, @Nullable String sessionId) {
        return "POST /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "MCP-Protocol-Version: 2025-11-25\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                + (sessionId == null ? "" : "MCP-Session-Id: " + sessionId + "\r\n")
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private Owner owner(CorsRawResponse initialized) {
        assertThat(initialized.status()).as(initialized.body()).isEqualTo(200);
        return engine().getSession(initialized.header("mcp-session-id"))
                .orElseThrow()
                .owner();
    }

    private static CorsRawResponse exchange(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        return CorsRawResponse.read(socket.getInputStream());
    }

    /** Writes every request at once, then reads one response to each, in order. */
    private List<CorsRawResponse> pipelined(String... requests) throws IOException {
        final var responses = new ArrayList<CorsRawResponse>();
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(String.join("", requests).getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            for (int i = 0; i < requests.length; i++) {
                responses.add(CorsRawResponse.read(socket.getInputStream()));
            }
        }
        return responses;
    }
}
