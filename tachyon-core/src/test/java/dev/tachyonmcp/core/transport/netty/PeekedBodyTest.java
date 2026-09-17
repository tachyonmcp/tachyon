/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PeekedBodyTest {

    private final ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
    private final EmbeddedChannel channel = new EmbeddedChannel(handler);
    private final ChannelHandlerContext ctx = channel.pipeline().context(handler);

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void secondPeekOfSameRequestReusesTheFirstParse() {
        var request = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        var first = PeekedBody.peek(ctx, request);
        var second = PeekedBody.peek(ctx, request);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
        request.release();
    }

    @Test
    void laterRequestOnSameChannelGetsItsOwnParse() {
        var first = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var second = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        var firstMessage = PeekedBody.peek(ctx, first);
        var secondMessage = PeekedBody.peek(ctx, second);

        assertThat(secondMessage).isNotNull().isNotSameAs(firstMessage);
        assertThat(PeekedBody.cached(ctx, first)).isNull();
        first.release();
        second.release();
    }

    @Test
    void malformedBodyCachesAsNullAndDoesNotRethrow() {
        var request = post("{ not json");

        assertThat(PeekedBody.peek(ctx, request)).isNull();
        assertThat(PeekedBody.peek(ctx, request)).isNull();

        var cached = PeekedBody.cached(ctx, request);
        assertThat(cached).isNotNull();
        assertThat(cached.message()).isNull();
        assertThat(cached.invalidRequest()).isFalse();
        request.release();
    }

    @Test
    void wellFormedJsonThatIsNotAJsonRpcEnvelopeCachesAsInvalidRequest() {
        var request = post("{\"hello\":\"world\"}");

        assertThat(PeekedBody.peek(ctx, request)).isNull();

        var cached = PeekedBody.cached(ctx, request);
        assertThat(cached).isNotNull();
        assertThat(cached.message()).isNull();
        assertThat(cached.invalidRequest()).isTrue();
        request.release();
    }

    @Test
    void cachedIsNullBeforeAnyPeekAndConsumesTheEntryAfterOne() {
        var request = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        assertThat(PeekedBody.cached(ctx, request)).isNull();

        var message = PeekedBody.peek(ctx, request);
        var cached = PeekedBody.cached(ctx, request);
        assertThat(cached).isNotNull();
        assertThat(cached.request()).isSameAs(request);
        assertThat(cached.message()).isSameAs(message);
        // Consumed: a second read finds nothing, so a re-peek cannot serve a stale entry.
        assertThat(PeekedBody.cached(ctx, request)).isNull();
        request.release();
    }

    @Test
    void clearDropsTheCachedParse() {
        var request = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        PeekedBody.peek(ctx, request);
        PeekedBody.clear(ctx);

        assertThat(PeekedBody.cached(ctx, request)).isNull();
        request.release();
    }

    private static FullHttpRequest post(String body) {
        return new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
    }
}
