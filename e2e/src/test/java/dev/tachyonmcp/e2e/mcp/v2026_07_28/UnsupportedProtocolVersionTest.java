/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * SEP-2575: a request naming a protocol version the server does not implement (unknown, or a
 * known version the server declined to support) must be rejected with an
 * UnsupportedProtocolVersionError whose {@code error.data} lists the versions the server does
 * support and echoes the requested one, over HTTP {@code 400 Bad Request}, with the response
 * {@code id} matching the request's JSON-RPC id.
 */
class UnsupportedProtocolVersionTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Test
    void rejectsUnsupportedProtocolVersion() throws Exception {
        // language=JSON
        var body = """
                {
                  "jsonrpc": "2.0",
                  "id": 301,
                  "method": "server/discover",
                  "params": {
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "v999.0.0",
                      "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"},
                      "io.modelcontextprotocol/clientCapabilities": {}
                    }
                  }
                }
                """;
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Origin", "http://localhost:3000")
                .header("MCP-Protocol-Version", "v999.0.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response).isJsonRpcError().hasId(301).hasErrorCode(-32022);
        assertThatJson(response.body()).inPath("$.error.data.requested").isEqualTo("v999.0.0");
        assertThatJson(response.body())
                .inPath("$.error.data.supported")
                .isArray()
                .contains("2026-07-28", "2025-11-25");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .as("error responses stay readable by the admitted page")
                .contains("*");
    }

    @Test
    void rejectsUnsupportedProtocolVersionWithUnparsableBodyUsingNullId() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "v999.0.0")
                .POST(HttpRequest.BodyPublishers.ofString("not json"))
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response).isJsonRpcError().hasId(null);
    }

    /**
     * An unsupported-version request the aggregator refuses (413) never reaches the rejection
     * handler, and Netty keeps a keep-alive connection open after it. The next request on that
     * connection, possibly another client's behind a pooling proxy, must not inherit its verdict.
     */
    @Test
    void refusedUnsupportedVersionRequestDoesNotPoisonNextRequestOnConnection() throws Exception {
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            out.write(("POST /mcp HTTP/1.1\r\n"
                            + "Host: localhost:" + port + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Accept: application/json, text/event-stream\r\n"
                            + "MCP-Protocol-Version: v999\r\n"
                            + "Expect: 100-continue\r\n"
                            + "Content-Length: 2000000\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            var refused = readResponse(in);

            // language=JSON
            var ping = """
                    {"jsonrpc":"2.0","id":2,"method":"ping"}""";
            out.write(("POST /mcp HTTP/1.1\r\n"
                            + "Host: localhost:" + port + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Accept: application/json, text/event-stream\r\n"
                            + "MCP-Protocol-Version: 2025-11-25\r\n"
                            + "Content-Length: " + ping.length() + "\r\n\r\n"
                            + ping)
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            var next = readResponse(in);

            assertThat(refused.statusLine()).isEqualTo("HTTP/1.1 413 Request Entity Too Large");
            assertThat(next.statusLine()).as(next.body()).isEqualTo("HTTP/1.1 200 OK");
            // language=JSON
            assertThatJson(next.body()).isEqualTo("""
                    {"jsonrpc":"2.0","id":2,"result":{}}
                    """);
        }
    }

    private record RawResponse(String statusLine, String body) {}

    private static RawResponse readResponse(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            var b = in.read();
            if (b < 0) throw new IOException("connection closed; received: " + head);
            head.write(b);
        }
        var lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        var contentLength = 0;
        for (var line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(
                        line.substring("content-length:".length()).trim());
            }
        }
        return new RawResponse(lines[0], new String(in.readNBytes(contentLength), StandardCharsets.UTF_8));
    }
}
