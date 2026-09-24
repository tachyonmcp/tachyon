/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpPipeliningGateTest {

    private EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningGate(16));

    @AfterEach
    void releaseChannel() {
        channel.finishAndReleaseAll();
    }

    @Test
    void holdsPipelinedRequestUntilStreamedResponseEnds() {
        var first = request("/a");
        assertThat(channel.writeInbound(first, body("a"))).isTrue();
        assertThat((Object) channel.readInbound()).isSameAs(first);
        assertThat(release(channel.<Object>readInbound())).isInstanceOf(LastHttpContent.class);
        assertThat(channel.config().isAutoRead())
                .as("no pipelining, reads untouched")
                .isTrue();

        var second = request("/b");
        var secondBody = body("b");
        assertThat(channel.writeInbound(second, secondBody)).isFalse();
        assertThat(channel.config().isAutoRead())
                .as("reads stop while a request is queued")
                .isFalse();

        channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        channel.writeOutbound(new DefaultHttpContent(Unpooled.copiedBuffer("data", StandardCharsets.UTF_8)));
        channel.runPendingTasks();
        assertThat((Object) channel.readInbound()).as("stream still open").isNull();

        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);
        channel.runPendingTasks();
        assertThat((Object) channel.readInbound()).isSameAs(second);
        assertThat((Object) channel.readInbound()).isSameAs(secondBody);
        assertThat(channel.config().isAutoRead()).isTrue();
        secondBody.release();
    }

    @Test
    void informationalResponseDoesNotReleaseQueue() {
        admitFirst();
        var second = request("/b");
        channel.writeInbound(second);

        channel.writeOutbound(response(HttpResponseStatus.CONTINUE, true));
        channel.runPendingTasks();
        assertThat((Object) channel.readInbound()).isNull();

        channel.writeOutbound(response(HttpResponseStatus.OK, true));
        channel.runPendingTasks();
        assertThat((Object) channel.readInbound()).isSameAs(second);
    }

    @Test
    void closingResponseDropsQueuedAndLaterRequests() {
        admitFirst();
        var queuedBody = body("b");
        channel.writeInbound(request("/b"), queuedBody);

        channel.writeOutbound(response(HttpResponseStatus.OK, false));
        channel.runPendingTasks();
        assertThat(queuedBody.refCnt()).isZero();

        var lateBody = body("c");
        assertThat(channel.writeInbound(request("/c"), lateBody)).isFalse();
        assertThat(lateBody.refCnt()).isZero();
    }

    @Test
    void refusesRequestBeyondCapWith429AfterAnsweringQueuedOnes() {
        useGate(2);
        admitFirst();
        assertThat(channel.writeInbound(request("/q1"), request("/q2"))).isFalse();
        var overflowBody = body("over");
        var lateBody = body("late");
        assertThat(channel.writeInbound(request("/over"), overflowBody, request("/late"), lateBody))
                .isFalse();
        assertThat(overflowBody.refCnt())
                .as("the request over the cap never runs")
                .isZero();
        assertThat(lateBody.refCnt()).as("nor anything read after it").isZero();
        assertThat(channel.config().isAutoRead()).isFalse();

        for (var uri : new String[] {"/q1", "/q2"}) {
            channel.writeOutbound(response(HttpResponseStatus.OK, true));
            channel.runPendingTasks();
            assertThat(HttpUtil.isKeepAlive(release(channel.readOutbound()))).isTrue();
            assertThat(channel.<HttpRequest>readInbound().uri()).isEqualTo(uri);
            assertThat(channel.isOpen()).isTrue();
        }
        channel.writeOutbound(response(HttpResponseStatus.OK, true));
        release(channel.readOutbound());
        channel.runPendingTasks();

        FullHttpResponse refusal = channel.readOutbound();
        try {
            assertThat(refusal.status()).isEqualTo(HttpResponseStatus.TOO_MANY_REQUESTS);
            assertThat(HttpUtil.isKeepAlive(refusal)).isFalse();
            assertThat(refusal.content().toString(StandardCharsets.UTF_8)).isEqualTo("Too many pipelined requests");
        } finally {
            refusal.release();
        }
        assertThat((Object) channel.readInbound()).isNull();
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void zeroCapRefusesFirstPipelinedRequest() {
        useGate(0);
        admitFirst();
        channel.writeInbound(request("/b"));

        channel.writeOutbound(response(HttpResponseStatus.OK, true));
        release(channel.readOutbound());
        channel.runPendingTasks();

        assertThat(release(channel.<FullHttpResponse>readOutbound()).status())
                .isEqualTo(HttpResponseStatus.TOO_MANY_REQUESTS);
        assertThat((Object) channel.readInbound()).isNull();
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void rejectsNegativeCap() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HttpPipeliningGate(-1));
    }

    @Test
    void releasesQueueOnClose() {
        admitFirst();
        var queuedBody = body("b");
        channel.writeInbound(request("/b"), queuedBody);

        channel.close();

        assertThat(queuedBody.refCnt()).isZero();
    }

    @Test
    void autoReadFollowsWritabilityOnlyWhileNothingIsQueued() {
        setWritable(false);
        assertThat(channel.config().isAutoRead()).isFalse();
        setWritable(true);
        assertThat(channel.config().isAutoRead()).isTrue();

        admitFirst();
        channel.writeInbound(request("/b"));
        setWritable(false);
        setWritable(true);
        assertThat(channel.config().isAutoRead())
                .as("a queued request keeps reads off")
                .isFalse();
    }

    private void useGate(int maxQueuedRequests) {
        channel.finishAndReleaseAll();
        channel = new EmbeddedChannel(new HttpPipeliningGate(maxQueuedRequests));
    }

    private void admitFirst() {
        channel.writeInbound(request("/a"));
        channel.readInbound();
    }

    private void setWritable(boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, writable);
        channel.runPendingTasks();
        assertThat(channel.isWritable()).isEqualTo(writable);
    }

    private static HttpRequest request(String uri) {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
    }

    private static LastHttpContent body(String text) {
        return new DefaultLastHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }

    private static DefaultFullHttpResponse response(HttpResponseStatus status, boolean keepAlive) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpUtil.setKeepAlive(response, keepAlive);
        return response;
    }

    private static <T> T release(T msg) {
        ReferenceCountUtil.release(msg);
        return msg;
    }
}
