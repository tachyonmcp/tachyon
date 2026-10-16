/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Bare HTTP verbs on {@code /mcp}, so session tests choose the token and session id of every request. */
final class RawSessionClient implements AutoCloseable {

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    RawSessionClient(int port) {
        this.port = port;
    }

    static String whoAmI(int id) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"whoami","arguments":{}}}
                """.formatted(id);
    }

    HttpResponse<String> post(@Nullable String token, @Nullable String sessionId, String body) throws Exception {
        final var builder = request(token, sessionId)
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A POST whose response is read as it arrives, for a tool call that streams. */
    CompletableFuture<HttpResponse<Stream<String>>> postStreaming(
            @Nullable String token, @Nullable String sessionId, String body) {
        final var builder = request(token, sessionId)
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines());
    }

    /** Completes when the response head arrives; a granted GET stream sends it with its first event. */
    CompletableFuture<HttpResponse<Stream<String>>> listen(
            @Nullable String token, String sessionId, @Nullable String lastEventId) {
        final var builder = request(token, sessionId).GET();
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines());
    }

    /** A GET that is expected to be refused, so the response is complete without a stream. */
    HttpResponse<Stream<String>> get(@Nullable String token, String sessionId, @Nullable String lastEventId)
            throws Exception {
        return listen(token, sessionId, lastEventId).get(5, TimeUnit.SECONDS);
    }

    HttpResponse<String> delete(@Nullable String token, String sessionId) throws Exception {
        return http.send(request(token, sessionId).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    String initialize(@Nullable String token) throws Exception {
        final var init = post(
                token,
                null,
                // language=JSON
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25","capabilities":{},
                           "clientInfo":{"name":"test","version":"1.0"}}}
                """);
        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
        final var sessionId = init.headers().firstValue("MCP-Session-Id").orElseThrow();
        final var initialized = post(token, sessionId, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        assertThat(initialized.statusCode()).isEqualTo(202);
        return sessionId;
    }

    private HttpRequest.Builder request(@Nullable String token, @Nullable String sessionId) {
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Accept", "application/json, text/event-stream");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (sessionId != null) {
            builder.header("MCP-Session-Id", sessionId);
        }
        return builder;
    }

    @Override
    public void close() {
        http.close();
    }
}
