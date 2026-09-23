/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProtocolVersionHandlerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel(new ProtocolVersionHandler());

    @AfterEach
    void tearDown() {
        channel.close();
    }

    @Test
    void unsupportedVersionFlagsRequestAndPassesThrough() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Protocol-Version", "2099-01-01");
        channel.writeInbound(request);

        // No response yet: rejection is deferred to UnsupportedProtocolVersionHandler, which runs
        // after http-aggregator so it can echo the request's JSON-RPC id.
        assertThat((Object) channel.readOutbound()).isNull();
        assertThat(channel.attr(ProtocolVersionHandler.UNSUPPORTED_VERSION_KEY).get())
                .isEqualTo("2099-01-01");
        channel.finishAndReleaseAll();
    }

    @Test
    void supportedVersionPassesThrough() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Protocol-Version", "2025-11-25");
        channel.writeInbound(request);

        assertThat((Object) channel.readOutbound()).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void latestVersionBindsItsProtocolToTheInteraction() {
        var negotiationChannel = new EmbeddedChannel(new ProtocolVersionHandler(), new InteractionHandler());
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        request.headers().set("MCP-Protocol-Version", McpProtocol.VERSION);

        negotiationChannel.writeInbound(request);

        assertThat(negotiationChannel
                        .attr(InteractionHandler.INTERACTION_CONTEXT_KEY)
                        .get())
                .extracting(context -> context.protocolVersion())
                .isEqualTo(McpProtocol.VERSION);
        negotiationChannel.finishAndReleaseAll();
    }

    @Test
    void latestVersionRebindsFreshInteractionOnEveryRequest() {
        // 2026-07-28 is stateless/per-request: a keep-alive connection carrying two requests must
        // never let the second one see state (e.g. negotiated extensions) left on the interaction
        // context by the first. A fresh EmbeddedChannel per request can't catch a regression to
        // setIfAbsent here — there'd be nothing yet to overwrite either way — so this reuses one
        // channel across both requests, the same way a real keep-alive connection would.
        var negotiationChannel = new EmbeddedChannel(new ProtocolVersionHandler(), new InteractionHandler());
        var firstRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        firstRequest.headers().set("MCP-Protocol-Version", McpProtocol.VERSION);
        negotiationChannel.writeInbound(firstRequest);
        var firstContext = negotiationChannel
                .attr(InteractionHandler.INTERACTION_CONTEXT_KEY)
                .get();

        var secondRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        secondRequest.headers().set("MCP-Protocol-Version", McpProtocol.VERSION);
        negotiationChannel.writeInbound(secondRequest);
        var secondContext = negotiationChannel
                .attr(InteractionHandler.INTERACTION_CONTEXT_KEY)
                .get();

        assertThat(secondContext).isNotSameAs(firstContext);
        negotiationChannel.finishAndReleaseAll();
    }

    @Test
    void missingVersionPassesThrough() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers().set(HttpHeaderNames.ORIGIN, "http://localhost:3000");
        channel.writeInbound(request);

        assertThat((Object) channel.readOutbound()).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void nonPostMethodPassesThrough() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Protocol-Version", "2099-01-01");
        channel.writeInbound(request);

        assertThat((Object) channel.readOutbound()).isNull();
        channel.finishAndReleaseAll();
    }
}
