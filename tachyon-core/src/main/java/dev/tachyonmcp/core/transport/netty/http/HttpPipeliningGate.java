/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admits one HTTP/1.1 request at a time per connection, so pipelined responses leave in request order
 * (RFC 9112 §9.3.2) however their asynchronous handlers complete.
 *
 * <p>A request read while the previous response is still open is queued, with its content, until that
 * response's {@link LastHttpContent} is written. Responses of every kind — validation errors, JSON,
 * {@code 202}, POST-SSE streams — pass through here, so none can overtake an earlier one. Informational
 * {@code 1xx} responses do not complete a request. A final response that is not keep-alive discards the
 * queue and every later request: the connection closes after it, and a request must not run without
 * being answered.
 *
 * <p>At most {@code maxQueuedRequests} requests wait behind the one in flight; {@code 0} disables
 * pipelining. The next request is refused: once every request ahead of it is answered, it gets
 * {@code 429 Too Many Requests} and the connection closes. Neither it nor anything read after it runs,
 * so the client may retry them.
 *
 * <p>Owns the channel's {@code autoRead}: reads stop while a request is queued, bounding the queue to
 * what one read decoded, and while the channel is not writable. Clients that do not pipeline never
 * queue, so their reads, and disconnect detection, are unaffected.
 *
 * <p>Install directly after the HTTP codec. One instance per channel; event-loop confined.
 */
@InternalApi
public final class HttpPipeliningGate extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpPipeliningGate.class);
    private static final Object OVERFLOW = new Object();

    private final int maxQueuedRequests;
    private final ArrayDeque<Object> queued = new ArrayDeque<>();
    private int queuedRequests;
    private boolean awaitingResponse;
    private boolean closeAfterResponse;
    private boolean closing;

    /**
     * @param maxQueuedRequests pipelined requests allowed to wait behind the one in flight; {@code 0}
     *                          disables pipelining
     * @throws IllegalArgumentException if negative
     */
    public HttpPipeliningGate(int maxQueuedRequests) {
        if (maxQueuedRequests < 0) {
            throw new IllegalArgumentException("maxQueuedRequests must not be negative");
        }
        this.maxQueuedRequests = maxQueuedRequests;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpObject)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (closing) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (!queued.isEmpty() || (awaitingResponse && msg instanceof HttpRequest)) {
            ctx.channel().config().setAutoRead(false);
            if (msg instanceof HttpRequest) {
                if (queuedRequests == maxQueuedRequests) {
                    logger.debug(
                            "Over {} pipelined requests, refusing with 429: {}",
                            maxQueuedRequests,
                            ctx.channel().remoteAddress());
                    ReferenceCountUtil.release(msg);
                    queued.add(OVERFLOW);
                    closing = true;
                    return;
                }
                queuedRequests++;
            }
            queued.add(msg);
            return;
        }
        if (msg instanceof HttpRequest) {
            awaitingResponse = true;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        final var informational =
                msg instanceof HttpResponse response && response.status().codeClass() == HttpStatusClass.INFORMATIONAL;
        if (msg instanceof HttpResponse response && !informational) {
            closeAfterResponse = !HttpUtil.isKeepAlive(response);
        }
        ctx.write(msg, promise);
        if (msg instanceof LastHttpContent && !informational && awaitingResponse) {
            awaitingResponse = false;
            if (closeAfterResponse) {
                closing = true;
                releaseQueued();
            } else if (!queued.isEmpty()) {
                ctx.executor().execute(() -> drain(ctx));
            }
        }
    }

    private void drain(ChannelHandlerContext ctx) {
        if (ctx.isRemoved()) {
            return;
        }
        while (!queued.isEmpty() && !(awaitingResponse && isRequestSlot(queued.peek()))) {
            final var next = queued.poll();
            if (next == OVERFLOW) {
                refuseOverflow(ctx);
                return;
            }
            if (next instanceof HttpRequest) {
                queuedRequests--;
                awaitingResponse = true;
            }
            ctx.fireChannelRead(next);
        }
        ctx.fireChannelReadComplete();
        if (queued.isEmpty() && !closing) {
            ctx.channel().config().setAutoRead(ctx.channel().isWritable());
        }
    }

    private static boolean isRequestSlot(Object queuedObject) {
        return queuedObject == OVERFLOW || queuedObject instanceof HttpRequest;
    }

    private static void refuseOverflow(ChannelHandlerContext ctx) {
        final var body = ByteBufUtil.writeUtf8(ctx.alloc(), "Too many pipelined requests");
        final var response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.TOO_MANY_REQUESTS, body);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                .set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        ctx.channel().config().setAutoRead(ctx.channel().isWritable() && queued.isEmpty() && !closing);
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseQueued();
    }

    private void releaseQueued() {
        Object next;
        while ((next = queued.poll()) != null) {
            ReferenceCountUtil.release(next);
        }
        queuedRequests = 0;
    }
}
