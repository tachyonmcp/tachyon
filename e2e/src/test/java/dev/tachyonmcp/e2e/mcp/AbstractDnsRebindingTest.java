/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.McpClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies DNS-rebinding protection (MCP transports: servers MUST validate {@code Origin}) and that a
 * rejected request is closed with a {@code Connection: close} header. Only the request shape differs
 * between protocol revisions, supplied by subclasses in {@code v2025_11_25}/{@code v2026_07_28}.
 *
 * <p>{@code Host} cases go over a raw socket: {@link HttpClient} refuses to set {@code Host}, and a
 * rebound {@code Host} is the attack the guard is named after.
 *
 * <p>Regression guard for a 50%-flaky conformance failure on Linux: the server used to reject
 * with a default keep-alive 403 and then close the socket anyway. Clients (e.g. undici) pooled
 * that socket, raced the next request onto it, and intermittently saw "other side closed".
 * Signalling {@code Connection: close} stops the client from reusing the socket.
 */
public abstract class AbstractDnsRebindingTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    private static final String EVIL_ORIGIN = "http://evil.example.com";

    /** Returns a JSON-RPC request this protocol revision accepts on a fresh stateless server. */
    protected abstract String requestBody();

    /** Returns the protocol headers {@link #requestBody()} needs when sent over a raw socket. */
    protected abstract Map<String, String> requestHeaders();

    @Test
    void rejectsNonLocalhostOriginAndSignalsClose() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin(EVIL_ORIGIN, requestBody());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("connection"))
                    .as("rejected requests must signal Connection: close so the client does not pool the socket")
                    .hasValueSatisfying(v -> assertThat(v).isEqualToIgnoringCase("close"));
        }
    }

    @Test
    void acceptsLocalhostOrigin() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin("http://localhost:" + port, requestBody());

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "http://localhost.evil.example.com",
                "http://127.0.0.1.evil.example.com",
                "http://[::1].evil.example.com",
                "http://evil.example.com/http://localhost",
                "http://localhost:80@evil.example.com"
            })
    void rejectsNonLoopbackLookalikeOrigin(String origin) throws Exception {
        assertRejected(origin);
    }

    /** {@code Origin} is a serialized origin, {@code scheme://host[:port]}: anything else is malformed. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost/",
                "http://localhost/path",
                "http://localhost?x",
                "http://localhost#x",
                "http://user@localhost",
                "localhost:3000",
                "ftp://localhost",
                "http:///x",
                "http://localhost:0",
                "http://localhost:65536",
                "http://[::1",
                "http://[::1]/"
            })
    void rejectsMalformedLoopbackOrigin(String origin) throws Exception {
        assertRejected(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost", "http://[::1]", "http://[::1]:3000", "HTTP://LOCALHOST:3000"})
    void acceptsLoopbackOriginSpellings(String origin) throws Exception {
        try (var client = createTestClient()) {
            assertThat(client.postWithOrigin(origin, requestBody()).statusCode())
                    .isEqualTo(200);
        }
    }

    private void assertRejected(String origin) throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin(origin, requestBody());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "DELETE"})
    void rejectsNonLocalhostOriginOnEveryMethod(String method) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Origin", EVIL_ORIGIN)
                .header("Accept", "text/event-stream")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();

        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode())
                    .as("the guard runs before method dispatch, so an SSE GET is refused too")
                    .isEqualTo(403);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .isEmpty();
        }
    }

    @Test
    void rejectsPreflightFromNonLocalhostOrigin() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Origin", EVIL_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode())
                    .as("the guard, not CORS, must refuse the preflight")
                    .isEqualTo(403);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .isEmpty();
            assertThat(response.headers().firstValue("connection"))
                    .hasValueSatisfying(v -> assertThat(v).isEqualToIgnoringCase("close"));
        }
    }

    @Test
    void preflightToOtherPathIsNotGranted() throws Exception {
        var response = preflight("http://localhost:3000", "/other");

        assertThat(response.statusCode())
                .as("only the MCP endpoint answers, so CORS grants never leak from other paths")
                .isEqualTo(404);
        assertThat(response.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:%d", "127.0.0.1:%d", "[::1]:%d"})
    void acceptsLoopbackHostOverRawSocket(String hostTemplate) throws Exception {
        var response = rawPost(hostTemplate.formatted(port), Map.of());

        assertThat(response.status()).isEqualTo(200);
    }

    /**
     * After rebinding, the attacker's page is same-origin with its own name, so the browser sends
     * {@code Host: attacker…} and, on a same-origin GET, no {@code Origin} at all. {@code 0.0.0.0} and
     * {@code [::]} reach loopback on Linux/macOS ("0.0.0.0 day").
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "attacker.example.com",
                "attacker.example.com:%d",
                "localhost.attacker.example.com:%d",
                "0.0.0.0:%d",
                "0.0.0.0",
                "[::]:%d"
            })
    void rejectsReboundHostWithoutOrigin(String hostTemplate) throws Exception {
        var response = rawPost(hostTemplate.formatted(port), Map.of());

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.header("connection")).isEqualToIgnoringCase("close");
    }

    @Test
    void rejectsReboundHostEvenWithLoopbackOrigin() throws Exception {
        var response = rawPost("attacker.example.com:" + port, Map.of("Origin", "http://localhost:" + port));

        assertThat(response.status())
                .as("Host is checked on its own; a loopback Origin must not excuse it")
                .isEqualTo(403);
    }

    @Test
    void rejectsPreflightToReboundHost() throws Exception {
        var host = "attacker.example.com:" + port;
        var response =
                raw("OPTIONS", host, Map.of("Origin", "http://" + host, "Access-Control-Request-Method", "POST"), "");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.header("access-control-allow-origin")).isNull();
    }

    private HttpResponse<String> preflight(String origin, String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private RawResponse rawPost(String host, Map<String, String> headers) throws IOException {
        var all = new LinkedHashMap<>(requestHeaders());
        all.putAll(headers);
        all.put("Content-Type", "application/json");
        all.put("Accept", "application/json, text/event-stream");
        return raw("POST", host, all, requestBody());
    }

    private RawResponse raw(String method, String host, Map<String, String> headers, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var head = new StringBuilder(method + " /mcp HTTP/1.1\r\nHost: " + host + "\r\n");
        headers.forEach(
                (name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("Connection: close\r\nContent-Length: ")
                .append(bytes.length)
                .append("\r\n\r\n");
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            return RawResponse.read(socket);
        }
    }

    private record RawResponse(int status, Map<String, String> headers) {

        @Nullable
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        static RawResponse read(Socket socket) throws IOException {
            var in = socket.getInputStream();
            var buffer = new ByteArrayOutputStream();
            int tail = 0;
            int b;
            while ((b = in.read()) != -1) {
                buffer.write(b);
                tail = (tail << 8) | b;
                if (tail == 0x0D0A0D0A) break;
            }
            var lines = buffer.toString(StandardCharsets.US_ASCII).split("\r\n");
            var status = Integer.parseInt(lines[0].split(" ")[1]);
            var headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                var colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(
                            lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }
            return new RawResponse(status, headers);
        }
    }
}
