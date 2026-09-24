/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * CORS headers belong to the request a response answers, not to whichever request the connection read
 * last. Two pipelined requests from different origins: the second is on the wire while the first is
 * still in flight, so a per-connection CORS state would stamp the first response with the second
 * request's origin.
 *
 * <p>The first request is slow and the second answers at once, yet HTTP/1.1 pipelined responses must
 * follow request order (RFC 9112 §9.3.2): JSON-RPC ids do not repair a client's positional response
 * association. Nothing may reach the socket until the first result is released.
 */
class CorsPipeliningTest extends AbstractStatelessMcpE2eTest<Mcp20251125Client> {

    private static final String ORIGIN_A = "http://localhost:3000";
    private static final String ORIGIN_B = "https://app.example.com";

    private final CountDownLatch firstStarted = new CountDownLatch(1);
    private final CompletableFuture<ToolResult> firstResult = new CompletableFuture<>();

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Override
    protected void startDefaultServer() {
        startServer(
                b -> b.capabilities(c -> c.tools()).network(n -> n.allowedOrigins(ORIGIN_A, ORIGIN_B)),
                s -> s.tools()
                        .registerAsync(
                                d -> d.name("controlled").description("id 1 completes when the test releases it"),
                                (ctx, request) -> {
                                    if (request.arguments().longOr("id", 0) != 1) {
                                        return CompletableFuture.completedFuture(ToolResult.text("second"));
                                    }
                                    firstStarted.countDown();
                                    return firstResult;
                                }));
    }

    @Test
    void eachPipelinedResponseCarriesItsOwnRequestsCorsGrant() throws Exception {
        var pipelined = post(1, ORIGIN_A) + post(2, ORIGIN_B);

        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            out.write(pipelined.getBytes(StandardCharsets.UTF_8));
            out.flush();
            var in = socket.getInputStream();

            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            await().during(Duration.ofMillis(300))
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(in.available())
                            .as("the fast second response must wait for the slow first")
                            .isZero());
            firstResult.complete(ToolResult.text("first"));

            var first = CorsRawResponse.read(in);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).as("responses must follow request order").contains("\"id\":1");
            assertThat(first.header("access-control-allow-origin"))
                    .as("the first response answers origin A, though origin B's request was sent after it")
                    .isEqualTo(ORIGIN_A);
            assertThat(first.header("vary")).containsIgnoringCase("origin");

            var second = CorsRawResponse.read(in);
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.body()).contains("\"id\":2");
            assertThat(second.header("access-control-allow-origin")).isEqualTo(ORIGIN_B);
            assertThat(second.header("vary")).containsIgnoringCase("origin");
        } finally {
            firstResult.complete(ToolResult.text("first"));
        }
    }

    private String post(int id, String origin) {
        // language=JSON
        var body = """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"controlled","arguments":{"id":%d}}}""".formatted(id, id);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        return "POST /mcp HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Origin: " + origin + "\r\n"
                + "MCP-Protocol-Version: 2025-11-25\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "\r\n"
                + body;
    }
}
