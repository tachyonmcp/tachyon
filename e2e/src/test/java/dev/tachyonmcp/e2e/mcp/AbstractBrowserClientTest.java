/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.McpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A browser page on a loopback dev port (e.g. a Vite app on {@code localhost:5173}) talking to the
 * server. Asserts what the browser's CORS check needs, not merely that a 200 came back: the preflight
 * must grant every header and method the client uses, and response headers the client reads must be
 * exposed to script. Headers, methods and the first request differ per protocol revision, supplied
 * by subclasses in {@code v2025_11_25}/{@code v2026_07_28}.
 */
public abstract class AbstractBrowserClientTest<C extends McpClient> extends AbstractMcpE2eTest<C> {

    private static final String DEV_ORIGIN = "http://localhost:5173";

    /** Fetch: GET, HEAD and POST need no {@code Access-Control-Allow-Methods} entry. */
    private static final Set<String> SAFELISTED_METHODS = Set.of("GET", "HEAD", "POST");

    @Override
    protected void startDefaultServer() {
        if (sessionMode() == SessionMode.STATEFUL) {
            super.startDefaultServer();
            return;
        }
        var h = SharedStatelessE2eServer.ensureStarted();
        this.server = h;
        this.port = h.port();
        this.usingCustomServer = false;
    }

    /** Returns the lower-case request headers a browser client of this revision sends. */
    protected abstract List<String> clientHeaders();

    /** Returns the HTTP methods a browser client of this revision uses. */
    protected abstract List<String> clientMethods();

    /** Returns the first request a browser client of this revision sends. */
    protected abstract String firstRequestBody();

    /** Returns the lower-case response headers a browser client of this revision must read. */
    protected abstract List<String> scriptReadableHeaders();

    @ParameterizedTest
    @MethodSource("clientMethods")
    void preflightGrantsEverythingABrowserClientSends(String method) throws Exception {
        var response = preflight(method, String.join(",", clientHeaders()), false);

        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.headers().firstValue("access-control-allow-origin"))
                .hasValueSatisfying(v -> assertThat(v).isIn("*", DEV_ORIGIN));
        assertThat(response.headers().firstValue("access-control-allow-credentials"))
                .as("the server never grants credentialed CORS")
                .isEmpty();
        var allowedMethods = tokens(response, "access-control-allow-methods");
        if (!SAFELISTED_METHODS.contains(method)) {
            assertThat(allowedMethods).containsAnyOf(method.toLowerCase(Locale.ROOT), "*");
        }
        var allowedHeaders = tokens(response, "access-control-allow-headers");
        assertThat(clientHeaders())
                .as("Fetch: '*' covers every header except Authorization, which must be listed")
                .allSatisfy(header -> assertThat(allowedHeaders.contains(header)
                                || (allowedHeaders.contains("*") && !header.equals("authorization")))
                        .as("preflight grants %s (granted: %s)", header, allowedHeaders)
                        .isTrue());
        assertThat(response.headers().firstValue("access-control-max-age"))
                .as("a zero max-age forces a preflight before every request")
                .hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
    }

    @Test
    void preflightDoesNotGrantPrivateNetworkAccessByDefault() throws Exception {
        var response = preflight("POST", "content-type", true);

        assertThat(response.headers().firstValue("access-control-allow-private-network"))
                .as("allowPrivateNetworks is off by default")
                .isEmpty();
    }

    @Test
    void firstResponseIsReadableFromScript() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin(DEV_ORIGIN, firstRequestBody());
            try {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"result\"");
                var allowOrigin = response.headers().firstValue("access-control-allow-origin");
                assertThat(allowOrigin).hasValueSatisfying(v -> assertThat(v).isIn("*", DEV_ORIGIN));
                if (allowOrigin.orElseThrow().equals(DEV_ORIGIN)) {
                    assertThat(tokens(response, "vary"))
                            .as("an echoed origin must carry Vary: Origin, or a shared cache serves it cross-origin")
                            .contains("origin");
                }
                assertThat(response.headers().firstValue("access-control-allow-credentials"))
                        .isEmpty();
                assertThat(tokens(response, "access-control-expose-headers"))
                        .as("fetch() hides unexposed headers from script")
                        .containsAll(scriptReadableHeaders());
            } finally {
                var sessionId = response.headers().firstValue("mcp-session-id");
                if (sessionId.isPresent()) client.delete(sessionId.get());
            }
        }
    }

    private HttpResponse<String> preflight(String method, String requestHeaders, boolean privateNetwork)
            throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Origin", DEV_ORIGIN)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", requestHeaders)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        if (privateNetwork) builder.header("Access-Control-Request-Private-Network", "true");
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static Set<String> tokens(HttpResponse<?> response, String header) {
        return response.headers().allValues(header).stream()
                .flatMap(v -> List.of(v.split(",")).stream())
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toSet());
    }
}
