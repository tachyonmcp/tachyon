/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.security.AuthenticationChallenge;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.core.server.security.AuthenticationProvider;
import dev.tachyonmcp.core.server.security.BearerTokenVerifier;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Fixtures for proving that a request is authenticated on its head, before its body is read: a raw
 * socket that stops after the headers, a tool that digests a large body, and verifiers that are slow
 * or never answer.
 */
public final class HeadAuthentication {

    /** Declared by the raw request below; above the default {@code maxContentLength} of 1 MB. */
    private static final int DECLARED_LENGTH = 2 * 1024 * 1024;

    private static final int PAYLOAD_LENGTH = 512 * 1024;

    public static final String DIGEST = "digest";

    private HeadAuthentication() {}

    /**
     * The first response to a tokenless {@code Expect: 100-continue} POST, and whether the server then
     * closed the connection.
     *
     * @param status the first status code on the wire; {@code 100} would mean the body was invited
     * @param challenge the {@code WWW-Authenticate} header, or {@code null}
     * @param closed whether the connection reached end of stream afterwards
     */
    public record Refusal(int status, @Nullable String challenge, boolean closed) {}

    /**
     * Sends only the head of a POST declaring a body above {@code maxContentLength}, with
     * {@code Expect: 100-continue} and no token, and reads the answer. The body is never sent: a client
     * that waits for {@code 100 Continue} sends nothing, and writing it anyway to a closing socket
     * would reset it.
     */
    public static Refusal refusalBeforeTheBody(int port, String protocolVersion) throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5_000);
            final var head = "POST /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Accept: application/json, text/event-stream\r\n"
                    + "MCP-Protocol-Version: " + protocolVersion + "\r\n"
                    + "Content-Length: " + DECLARED_LENGTH + "\r\n"
                    + "Expect: 100-continue\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            final var response = CorsRawResponse.read(socket.getInputStream());
            boolean closed;
            try {
                closed = socket.getInputStream().read() == -1;
            } catch (SocketTimeoutException e) {
                closed = false;
            }
            return new Refusal(response.status(), response.header("www-authenticate"), closed);
        }
    }

    /**
     * What a tokenless POST was answered with, read off the socket, so a header the server wrote is
     * seen exactly as written.
     *
     * @param status the status code
     * @param headers the response headers by lower-cased name; repeated ones joined by {@code ", "}
     */
    public record RawAnswer(int status, Map<String, String> headers) {}

    /** Sends a complete small POST with no credentials and reads the answer off the socket. */
    public static RawAnswer anonymousPost(int port, String protocolVersion) throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5_000);
            final var request = "POST /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Accept: application/json, text/event-stream\r\n"
                    + "MCP-Protocol-Version: " + protocolVersion + "\r\n"
                    + "Content-Length: 2\r\n\r\n{}";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            final var response = CorsRawResponse.read(socket.getInputStream());
            return new RawAnswer(response.status(), response.headers());
        }
    }

    public static final String API_KEY = "k-123";

    private static final AuthenticationChallenge API_KEY_CHALLENGE =
            AuthenticationChallenge.of("ApiKey").withParam("realm", "mcp");

    /** A provider for the {@code ApiKey} scheme, which answers in that scheme and never in Bearer. */
    public static AuthenticationProvider apiKey() {
        return request -> {
            final var keys = request.headers("X-Api-Key");
            if (keys.isEmpty()) {
                throw AuthenticationException.unauthorized("No API key", API_KEY_CHALLENGE);
            }
            if (!keys.getFirst().equals(API_KEY)) {
                throw AuthenticationException.unauthorized(
                        "Unknown API key", API_KEY_CHALLENGE.withParam("error", "invalid_key"));
            }
            return SecurityContext.authenticated(() -> "service", List.of());
        };
    }

    /** A provider whose challenge parameter is meant to smuggle a header in; the value is refused. */
    public static AuthenticationProvider injectingAHeader() {
        return request -> {
            throw AuthenticationException.unauthorized(
                    "No API key", AuthenticationChallenge.of("ApiKey").withParam("realm", "mcp\r\nX-Injected: yes"));
        };
    }

    /** A provider whose challenge parameter holds quotes, which the header must escape. */
    public static AuthenticationProvider quotingInTheChallenge() {
        return request -> {
            throw AuthenticationException.unauthorized(
                    "No API key", AuthenticationChallenge.of("ApiKey").withParam("realm", "say \"hi\""));
        };
    }

    /** A body larger than one socket read, with no repeating run long enough to hide a lost chunk. */
    public static String payload() {
        final var characters = "abcdefghijklmnopqrstuvwxyz0123456789";
        final var payload = new StringBuilder(PAYLOAD_LENGTH);
        for (int i = 0; i < PAYLOAD_LENGTH; i++) {
            payload.append(characters.charAt((i * 7 + i / 31) % characters.length()));
        }
        return payload.toString();
    }

    public static String digestOf(String payload) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Registers {@value #DIGEST}: answers the SHA-256 of its {@code payload} argument. */
    public static void registerDigest(TachyonServer server) {
        server.tools()
                .register(
                        d -> d.name(DIGEST).description("Digests its payload"),
                        (ctx, request) ->
                                ToolResult.text(digestOf(request.arguments().stringOr("payload", ""))));
    }

    /** Verifies as {@code verifier} does, after a pause standing in for a slow introspection call. */
    public static BearerTokenVerifier delaying(BearerTokenVerifier verifier, Duration delay) {
        return token -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while delayed", e);
            }
            return verifier.verify(token);
        };
    }

    /** Never answers until {@code release} opens, like an introspection endpoint that hangs. */
    public static BearerTokenVerifier hanging(CountDownLatch release) {
        return token -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw AuthenticationException.bearerInvalidToken("released");
        };
    }
}
