/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CorsExpectationTest extends AbstractStatelessMcpE2eTest<Mcp20251125Client> {

    private static final String APP = "https://app.example.com";
    private static final String SECOND_APP = "https://second.example.com";
    private static final String UNLISTED = "http://localhost:4000";

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
        startServer(b -> b.network(n -> n.allowedOrigins(APP, SECOND_APP).maxContentLength(1024)));
    }

    @ParameterizedTest
    @CsvSource({"100-continue,2048,413", "unsupported,0,417"})
    void expectationErrorsKeepTheirOwnCorsDecision(String expectation, int length, int status) throws Exception {
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            for (var origin : new String[] {APP, SECOND_APP, UNLISTED}) {
                var request = headers(origin, length, "Expect: " + expectation + "\r\n");
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                var response = CorsRawResponse.read(socket.getInputStream());
                assertThat(response.status()).isEqualTo(status);
                assertThat(response.header("vary")).containsIgnoringCase("origin");
                assertThat(response.header("access-control-allow-credentials")).isNull();
                assertThat(response.body()).isEmpty();
                if (origin.equals(UNLISTED)) {
                    assertThat(response.header("access-control-allow-origin")).isNull();
                    assertThat(response.header("access-control-expose-headers")).isNull();
                } else {
                    assertThat(response.header("access-control-allow-origin")).isEqualTo(origin);
                    assertThat(response.header("access-control-expose-headers"))
                            .contains("MCP-Session-Id", "MCP-Protocol-Version");
                }
            }
            // language=JSON
            var body = """
                    {"jsonrpc":"2.0","id":7,"method":"ping"}""";
            socket.getOutputStream()
                    .write((headers(APP, body.length(), "") + body).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = CorsRawResponse.read(socket.getInputStream());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("access-control-allow-origin")).isEqualTo(APP);
            assertThat(response.body()).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}");
        }
    }

    private String headers(String origin, int length, String extra) {
        return "POST /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Origin: " + origin + "\r\n"
                + "MCP-Protocol-Version: 2025-11-25\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "Content-Length: " + length + "\r\n" + extra + "\r\n";
    }
}
