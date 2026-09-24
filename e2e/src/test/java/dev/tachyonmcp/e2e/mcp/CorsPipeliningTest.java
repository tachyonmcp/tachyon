/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * CORS headers belong to the request a response answers, not to whichever request the connection read
 * last. Two pipelined requests from different origins run concurrently: the second is read while the
 * first is still in flight, so a per-connection CORS state would stamp the first response with the
 * second request's origin.
 *
 * <p>The second request finishes last, so the responses arrive in request order. The reader enforces
 * that order: HTTP/1.1 pipelined responses must follow request order (RFC 9112 §9.3.2).
 */
class CorsPipeliningTest extends AbstractStatelessMcpE2eTest<Mcp20251125Client> {

    private static final String ORIGIN_A = "http://localhost:3000";
    private static final String ORIGIN_B = "https://app.example.com";

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
                                d -> d.name("slow").description("Completes after the given milliseconds"),
                                (ctx, request) -> CompletableFuture.supplyAsync(
                                        () -> ToolResult.text("done"),
                                        CompletableFuture.delayedExecutor(
                                                request.arguments().longOr("ms", 0), TimeUnit.MILLISECONDS))));
    }

    @Test
    void eachPipelinedResponseCarriesItsOwnRequestsCorsGrant() throws Exception {
        var pipelined = post(1, 300, ORIGIN_A) + post(2, 900, ORIGIN_B);

        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            out.write(pipelined.getBytes(StandardCharsets.UTF_8));
            out.flush();
            var in = socket.getInputStream();

            var first = RawResponse.read(in);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).as("responses must follow request order").contains("\"id\":1");
            assertThat(first.header("access-control-allow-origin"))
                    .as("the first response answers origin A, though origin B's request was read after it")
                    .isEqualTo(ORIGIN_A);
            assertThat(first.header("vary")).containsIgnoringCase("origin");

            var second = RawResponse.read(in);
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.body()).contains("\"id\":2");
            assertThat(second.header("access-control-allow-origin")).isEqualTo(ORIGIN_B);
            assertThat(second.header("vary")).containsIgnoringCase("origin");
        }
    }

    private String post(int id, long ms, String origin) {
        // language=JSON
        var body = """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"slow","arguments":{"ms":%d}}}""".formatted(id, ms);
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

    private record RawResponse(int status, Map<String, String> headers, String body) {

        @Nullable
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        static RawResponse read(InputStream in) throws IOException {
            var head = new ByteArrayOutputStream();
            int tail = 0;
            int b;
            while ((b = in.read()) != -1) {
                head.write(b);
                tail = (tail << 8) | b;
                if (tail == 0x0D0A0D0A) break;
            }
            var lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
            var status = Integer.parseInt(lines[0].split(" ")[1]);
            var headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                var colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.merge(
                            lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim(),
                            (x, y) -> x + ", " + y);
                }
            }
            var length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            var body = in.readNBytes(length);
            return new RawResponse(status, headers, new String(body, StandardCharsets.UTF_8));
        }
    }
}
