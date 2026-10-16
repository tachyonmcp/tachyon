/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.testkit.McpClient;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The caller of a request is the caller of that request and no other: an identity authenticated for
 * one request on a connection must not reach the handler of the next, whether the next arrives in the
 * same write (HTTP pipelining) or later on the same pooled connection. The provider answers
 * {@code anonymous} when there is no credential, so a stale identity would show up as the wrong name.
 * Only the request shape differs between protocol revisions, supplied by subclasses.
 */
public abstract class AbstractCallerIsolationTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    private static final String ALICE = "alice [tools:read]";
    private static final String BOB = "bob []";
    private static final String ANONYMOUS = "anonymous";

    /** Returns a {@code tools/call} of {@code tool} with no arguments, as this protocol revision words it. */
    protected abstract String toolCallBody(String tool);

    /** Returns the protocol headers a {@code tools/call} of {@code tool} carries. */
    protected abstract Map<String, String> toolCallHeaders(String tool);

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
    void requestsInOneWriteEachSeeTheirOwnCaller() throws Exception {
        final var forward = pipelined(call("alice"), call(null), call("bob"));
        final var backward = pipelined(call(null), call("bob"), call("alice"));

        assertThat(forward).hasSize(3);
        assertThat(forward.get(0).body()).contains(ALICE);
        assertThat(forward.get(1).body()).contains(ANONYMOUS).doesNotContain("alice");
        assertThat(forward.get(2).body()).contains(BOB).doesNotContain("alice");
        assertThat(backward).hasSize(3);
        assertThat(backward.get(0).body()).contains(ANONYMOUS);
        assertThat(backward.get(1).body()).contains(BOB);
        assertThat(backward.get(2).body()).contains(ALICE).doesNotContain("bob");
    }

    @Test
    void aPooledConnectionDoesNotCarryTheCallerToTheNextRequest() throws Exception {
        final var responses = sequential(call("alice"), call(null), call("bob"), call(null), call("alice"));

        assertThat(responses.stream().map(CorsRawResponse::status)).containsOnly(200);
        assertThat(responses.get(0).body()).contains(ALICE);
        assertThat(responses.get(1).body()).contains(ANONYMOUS);
        assertThat(responses.get(2).body()).contains(BOB);
        assertThat(responses.get(3).body()).contains(ANONYMOUS);
        assertThat(responses.get(4).body()).contains(ALICE);
    }

    private String call(@Nullable String token) {
        final var body = toolCallBody(TestBearerTokens.WHOAMI);
        final var lines = new StringBuilder();
        toolCallHeaders(TestBearerTokens.WHOAMI)
                .forEach((name, value) ->
                        lines.append(name).append(": ").append(value).append("\r\n"));
        return "POST /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + lines
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
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

    /** Sends the requests one after another on one connection, reading each response before the next. */
    private List<CorsRawResponse> sequential(String... requests) throws IOException {
        final var responses = new ArrayList<CorsRawResponse>();
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            for (final var request : requests) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                responses.add(CorsRawResponse.read(socket.getInputStream()));
            }
        }
        return responses;
    }
}
