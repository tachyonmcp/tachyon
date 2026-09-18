/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpInitializationHandlerTest {

    private ServerEngine server;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        server =
                (ServerEngine) TachyonServer.builder().session(s -> s.enabled()).build();
        McpDispatcher dispatcher = new McpDispatcher(server, Runnable::run);
        channel = new EmbeddedChannel(new ProtocolVersionHandler("/mcp"), new InteractionHandler());
        channel.pipeline()
                .addLast(
                        McpHandlerManager.HANDLER_INIT,
                        new McpInitializationHandler(server, dispatcher, Runnable::run));
        channel.pipeline()
                .addLast(
                        "lifecycle",
                        new LifecyclePipelineCoordinator(new McpHandlerManager(server, dispatcher, Runnable::run)));
    }

    @AfterEach
    void tearDown() {
        channel.close();
        server.close();
    }

    @Test
    void optionsReturns204() {
        sendOptions("http://localhost:3000");
        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NO_CONTENT);
        response.release();
    }

    @Test
    void optionsRejectsMissingOrigin() {
        sendOptions(null);
        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        response.release();
    }

    @Test
    void initializeReturnsSessionId() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get("MCP-Session-Id")).isNotNull();
        response.release();
    }

    @Test
    void initializeTransitionsToOperationPhase() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var initResponse = readResponse();
        var sessionId = initResponse.headers().get("MCP-Session-Id");
        assertThat(sessionId).isNotNull();
        initResponse.release();

        assertThat(server.getSession(sessionId)).isPresent().map(Session::id).hasValue(sessionId);

        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void pingWithLatestProtocolUsesModernEmptyResult() {
        var body = "{" + "\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers().set("MCP-Protocol-Version", McpProtocol.VERSION);

        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThatJson(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("""
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "result": {"resultType": "complete"}
                        }
                        """);
        response.release();
    }

    @Test
    void postNotificationWithoutSessionReturnsSessionError() {
        var body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("Missing MCP-Session-Id header");
        response.release();
    }

    @Test
    void postNonInitializeWithoutSessionReturnsSessionError() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unknown/method\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        var content = response.content().toString(StandardCharsets.UTF_8);
        assertThat(content).contains("Missing MCP-Session-Id");
        assertThat(content).doesNotContain("jsonrpc");
        response.release();
    }

    @Test
    void postWithExistingSessionForwardsToOperationPhase() {
        server.createSession("sess-pre").activate();

        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Session-Id", "sess-pre")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        var content = response.content().toString(StandardCharsets.UTF_8);
        assertThat(content).contains("result");
        response.release();
    }

    @Test
    void getWithoutSessionForwardsToOperationPhase() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        response.release();
    }

    @Test
    void deleteWithoutSessionForwardsToOperationPhase() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, "/mcp");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://localhost:3000");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        response.release();
    }

    private void sendOptions(@Nullable String origin) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/mcp");
        if (origin != null) {
            request.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        channel.writeInbound(request);
    }

    private FullHttpResponse readResponse() {
        channel.runPendingTasks();
        var msg = channel.readOutbound();
        assertThat(msg).isInstanceOf(FullHttpResponse.class);
        return (FullHttpResponse) msg;
    }
}
