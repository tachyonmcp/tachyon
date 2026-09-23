/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.McpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@code NetworkConfig.allowedOrigins}/{@code allowNullOrigin} admit a browser origin
 * end-to-end: the DNS-rebinding guard must not reject an origin the CORS allowlist accepts. Only the
 * client and request body differ, supplied by subclasses in {@code v2025_11_25}/{@code v2026_07_28}.
 */
public abstract class AbstractCorsAllowedOriginsTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    private static final String APP_ORIGIN = "https://app.example.com";

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /** Returns a JSON-RPC request this protocol version accepts on a fresh stateless server. */
    protected abstract String requestBody();

    @Override
    protected void startDefaultServer() {
        startServer(b -> b.network(n -> n.allowedOrigins(APP_ORIGIN).allowNullOrigin(true)));
    }

    @Test
    void postFromAllowedOriginIsAccepted() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin(APP_ORIGIN, requestBody());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .hasValue(APP_ORIGIN);
        }
    }

    @Test
    void preflightFromAllowedOriginIsAccepted() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Origin", APP_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("access-control-allow-origin")).hasValue(APP_ORIGIN);
    }

    @Test
    void postFromNullOriginIsAcceptedWhenAllowed() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin("null", requestBody());

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void postFromUnlistedOriginIsStillRejected() throws Exception {
        try (var client = createTestClient()) {
            var response = client.postWithOrigin("https://evil.example.com", requestBody());

            assertThat(response.statusCode()).isEqualTo(403);
        }
    }
}
