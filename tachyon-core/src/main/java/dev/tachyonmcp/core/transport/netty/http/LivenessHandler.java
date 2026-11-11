/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Answers liveness probes on one path: {@code GET} and {@code HEAD} get {@code 200} with {@code UP}
 * as {@code text/plain}, or {@code {"status":"UP"}} when {@code Accept} names {@code application/json}.
 * {@code HEAD} gets the {@code GET} headers and no body. Any other method is {@code 405} with {@code
 * Allow: GET, HEAD} and the connection closes. A probe that declares a body ({@code Content-Length},
 * chunked) is answered and the connection closes without reading the body. Everything else passes untouched after one string
 * comparison.
 *
 * <p>Sits right after {@code http-keep-alive}, ahead of the DNS-rebinding guard, CORS and
 * authentication: a Kubernetes probe sends its pod IP as {@code Host} and carries no token, and the
 * answer holds no data those guards would protect. Both bodies are rendered once into unreleasable
 * buffers and every answer duplicates one, so nothing is allocated or released per probe beyond the
 * response.
 *
 * <p>Installed only when {@code NetworkConfig#livenessPath} is set. Stateless, so one instance serves
 * every channel.
 */
@Sharable
@InternalApi
public final class LivenessHandler extends ChannelInboundHandlerAdapter {

    private static final String ALLOWED_METHODS = "GET, HEAD";
    private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=UTF-8";
    private static final ByteBuf TEXT = body("UP");
    // language=JSON
    private static final ByteBuf JSON = body("{\"status\":\"UP\"}");

    private final String path;

    public LivenessHandler(String path) {
        this.path = EndpointValidatorHandler.normalizedPath(path);
    }

    private static ByteBuf body(String content) {
        return Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpRequest request)
                || !request.uri().startsWith(path)
                || !path.equals(EndpointValidatorHandler.normalizedPath(request.uri()))) {
            ctx.fireChannelRead(msg);
            return;
        }
        final var head = request.method() == HttpMethod.HEAD;
        if (request.method() != HttpMethod.GET && !head) {
            final var refusal = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED, Unpooled.EMPTY_BUFFER);
            refusal.headers().set(HttpHeaderNames.ALLOW, ALLOWED_METHODS);
            refusal.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            HttpUtil.setKeepAlive(refusal, false);
            ChannelHandlerUtils.markRejected(ctx, request);
            ctx.writeAndFlush(refusal);
            return;
        }
        final var json = wantsJson(request);
        final var body = json ? JSON : TEXT;
        final var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, head ? Unpooled.EMPTY_BUFFER : body.duplicate());
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, json ? HttpHeaderValues.APPLICATION_JSON : TEXT_PLAIN_UTF8)
                .set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        // Ahead of the aggregator and its size limit: a declared body would stream unbounded and
        // unauthenticated. Answer, drop the rest (dns-rebinding discards it), close.
        if (ChannelHandlerUtils.declaresBody(request)) {
            HttpUtil.setKeepAlive(response, false);
            ChannelHandlerUtils.markRejected(ctx, request);
        } else {
            HttpUtil.setKeepAlive(response, HttpUtil.isKeepAlive(request));
            ReferenceCountUtil.release(request);
        }
        ctx.writeAndFlush(response);
    }

    // ponytail: substring match, ignores q-values; parse ranges if a probe ever sends application/json;q=0
    private static boolean wantsJson(HttpRequest request) {
        final var accept = request.headers().get(HttpHeaderNames.ACCEPT);
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json");
    }
}
