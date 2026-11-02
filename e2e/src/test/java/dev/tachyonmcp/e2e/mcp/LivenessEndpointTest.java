/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestServers;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The liveness probe ({@code specs/features/liveness.md}): answered ahead of every guard, so a
 * Kubernetes probe with a pod-IP {@code Host} and no token gets {@code 200} even from a bearer-protected
 * server.
 */
class LivenessEndpointTest extends AbstractStatelessMcpE2eTest<McpClient> {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Override
    protected void startDefaultServer() {
        startServer(b -> b.network(n -> n.liveness()).security(TestBearerTokens.secured(new TestBearerTokens())));
    }

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/livez", "/livez/", "/livez?probe=k8s"})
    void getAnswersPlainTextUpWithoutToken(String path) throws Exception {
        var response = send(port, path, "GET", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/plain; charset=UTF-8");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.body()).isEqualTo("UP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json, text/plain;q=0.5", "*/*, application/json"})
    void jsonAcceptGetsJsonStatus(String accept) throws Exception {
        var response = send(port, "/livez", "GET", accept);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        // language=JSON
        assertThatJson(response.body()).isEqualTo("""
                {"status":"UP"}
                """);
    }

    @Test
    void headCarriesGetHeadersWithoutBody() throws Exception {
        var response = send(port, "/livez", "HEAD", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/plain; charset=UTF-8");
        assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(2);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void otherMethodsAreNotAllowed() throws Exception {
        var response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/livez"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValue("GET, HEAD");
    }

    @Test
    void foreignHostProbeOnKeepAliveConnectionIsAnsweredAndMcpStillGuarded() throws Exception {
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            var in = socket.getInputStream();

            write(socket, "GET /livez HTTP/1.1\r\nHost: 10.1.2.3:8080\r\n\r\n");
            var first = CorsRawResponse.read(in);
            write(socket, "GET /livez HTTP/1.1\r\nHost: 10.1.2.3:8080\r\nAccept: application/json\r\n\r\n");
            var second = CorsRawResponse.read(in);
            write(
                    socket,
                    "POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                            + "Accept: application/json, text/event-stream\r\nContent-Length: 2\r\n\r\n{}");
            var mcp = CorsRawResponse.read(in);

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).isEqualTo("UP");
            assertThat(first.header("Connection")).isNull();
            assertThat(second.status()).isEqualTo(200);
            assertThatJson(second.body()).isEqualTo("{\"status\":\"UP\"}");
            assertThat(mcp.status()).isEqualTo(401);
        }
    }

    /**
     * A probe that declares a body is answered, then the connection closes without waiting for the
     * body: the probe sits ahead of the aggregator's size limit, so an unfinished body must not keep
     * an unauthenticated stream flowing.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /livez HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n",
                "GET /livez HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000000\r\n\r\nabc"
            })
    void probeDeclaringBodyClosesConnectionBeforeBodyEnds(String unfinishedRequest) throws Exception {
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            var in = socket.getInputStream();

            write(socket, unfinishedRequest);
            var response = CorsRawResponse.read(in);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("Connection")).isEqualToIgnoringCase("close");
            assertThat(in.read()).as("server closed the connection").isEqualTo(-1);
        }
    }

    @Test
    void disabledByDefault() throws Exception {
        try (var plain = start(b -> {})) {
            assertThat(send(plain.port(), "/livez", "GET", null).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void customPathReplacesDefault() throws Exception {
        try (var custom = start(b -> b.network(n -> n.liveness("/health/live")))) {
            var live = send(custom.port(), "/health/live", "GET", null);

            assertThat(live.statusCode()).isEqualTo(200);
            assertThat(live.body()).isEqualTo("UP");
            assertThat(send(custom.port(), "/livez", "GET", null).statusCode()).isEqualTo(404);
        }
    }

    private static TachyonServer start(Consumer<ServerBuilder> configurer) {
        var builder = TachyonServer.builder().port(0).stateless();
        configurer.accept(builder);
        return McpTestServers.startSafely(builder, s -> {});
    }

    private static HttpResponse<String> send(int port, String path, String method, @Nullable String accept)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (accept != null) {
            request.header("Accept", accept);
        }
        return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void write(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
}
