/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.McpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A server configured with {@code allowedOrigins}: the DNS-rebinding guard admits those origins on
 * top of loopback, and CORS grants only them. Entries are canonicalized (lower case, default port
 * dropped), so a configured {@code https://App.Example.com:443} matches the browser's {@code
 * https://app.example.com}; any other port is a different origin. {@code Origin: null} is never
 * admitted. Only the request shape
 * differs between protocol revisions, supplied by subclasses in {@code v2025_11_25}/{@code
 * v2026_07_28}.
 */
public abstract class AbstractCorsOriginAllowlistTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    private static final String APP_ORIGIN = "https://app.example.com";
    private static final String IPV6_ORIGIN = "http://[2001:db8::1]:8080";
    private static final String EXPANDED_IPV6_ORIGIN = "http://[2001:0db8:0:0:0:0:0:1]:8080";
    private static final String LOOPBACK_ORIGIN = "http://localhost:3000";

    /** Returns a JSON-RPC request this protocol revision accepts on a fresh stateless server. */
    protected abstract String requestBody();

    @Override
    protected void startDefaultServer() {
        startServer(b -> b.network(n ->
                n.allowedOrigins("https://App.Example.com:443", EXPANDED_IPV6_ORIGIN, "http://[::ffff:192.0.2.1]")));
    }

    @Test
    void admitsListedRemoteOrigin() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin(APP_ORIGIN, requestBody());

            assertThat(response.statusCode())
                    .as("allowedOrigins admits a browser origin the loopback guard would refuse")
                    .isEqualTo(200);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .contains(APP_ORIGIN);
            assertThat(tokens(response, "vary"))
                    .as("a per-origin grant must carry Vary: Origin, or a shared cache serves it cross-origin")
                    .contains("origin");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {APP_ORIGIN, "HTTPS://App.Example.com:443"})
    void grantsPreflightFromListedRemoteOrigin(String origin) throws Exception {
        var response = preflight(origin);

        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.headers().firstValue("access-control-allow-origin")).contains(origin);
        assertThat(tokens(response, "vary")).contains("origin");
        assertThat(tokens(response, "access-control-allow-methods")).contains("get", "post", "delete");
        assertThat(response.headers().firstValue("access-control-allow-credentials"))
                .isEmpty();
        assertThat(tokens(response, "access-control-allow-headers")).contains("content-type", "mcp-param-city");
    }

    @Test
    void originListNarrowsCorsForLoopbackOrigins() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin(LOOPBACK_ORIGIN, requestBody());

            assertThat(response.statusCode())
                    .as("the guard still admits loopback; CORS decides what script may read")
                    .isEqualTo(200);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .as("an origin outside allowedOrigins must not be echoed")
                    .isEmpty();
            assertThat(tokens(response, "vary"))
                    .as("with an origin list the response depends on Origin even without a grant")
                    .contains("origin");
        }
        var denied = preflight(LOOPBACK_ORIGIN);
        assertThat(denied.headers().firstValue("access-control-allow-origin")).isEmpty();
        assertThat(denied.headers().firstValue("access-control-allow-headers")).isEmpty();
        assertThat(tokens(denied, "vary")).contains("origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {IPV6_ORIGIN, EXPANDED_IPV6_ORIGIN, "http://[::ffff:c000:201]", "http://[::ffff:192.0.2.1]"})
    void admitsEquivalentIpv6Origins(String origin) throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin(origin, requestBody());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .contains(origin);
            assertThat(tokens(response, "vary")).contains("origin");
        }
        var preflight = preflight(origin);
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("access-control-allow-origin"))
                .contains(origin);
        assertThat(tokens(preflight, "vary")).contains("origin");
    }

    @Test
    void rejectsUnlistedRemoteOrigin() throws Exception {
        try (var client = createTestClient()) {
            assertThat(client.postWithOrigin("http://evil.example.com", requestBody())
                            .statusCode())
                    .isEqualTo(403);
            assertThat(client.postWithOrigin(APP_ORIGIN + ":444", requestBody()).statusCode())
                    .as("a different port is a different origin")
                    .isEqualTo(403);
            assertThat(client.postWithOrigin("http://app.example.com", requestBody())
                            .statusCode())
                    .as("a different scheme is a different origin")
                    .isEqualTo(403);
            assertThat(client.postWithOrigin("http://[::192.0.2.1]", requestBody())
                            .statusCode())
                    .as("IPv4-compatible and IPv4-mapped IPv6 addresses must remain distinct")
                    .isEqualTo(403);
            assertThat(client.postWithOrigin("http://[2001:db8::2]:8080", requestBody())
                            .statusCode())
                    .as("a different IPv6 address is a different origin")
                    .isEqualTo(403);
            assertThat(client.postWithOrigin("http://[2001:db8::1]", requestBody())
                            .statusCode())
                    .isEqualTo(403);
            assertThat(client.postWithOrigin(APP_ORIGIN + "/", requestBody()).statusCode())
                    .as("a path makes the value malformed, even for a listed origin")
                    .isEqualTo(403);
        }
        var preflight = preflight("http://evil.example.com");
        assertThat(preflight.statusCode()).isEqualTo(403);
        assertThat(preflight.headers().firstValue("access-control-allow-origin"))
                .isEmpty();
    }

    @Test
    void rejectsNullOrigin() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin("null", requestBody());

            assertThat(response.statusCode())
                    .as("any page sends Origin: null from a sandboxed iframe, so the guard never admits it")
                    .isEqualTo(403);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .isEmpty();
        }
        var preflight = preflight("null");
        assertThat(preflight.statusCode()).isEqualTo(403);
        assertThat(preflight.headers().firstValue("access-control-allow-origin"))
                .isEmpty();
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type, Mcp-Param-City")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static Set<String> tokens(HttpResponse<?> response, String header) {
        return response.headers().allValues(header).stream()
                .flatMap(v -> Arrays.stream(v.split(",")))
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
