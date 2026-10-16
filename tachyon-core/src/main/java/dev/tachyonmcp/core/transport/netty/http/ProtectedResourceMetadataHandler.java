/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.server.config.BearerConfig;
import dev.tachyonmcp.core.server.security.ProtectedResourceMetadata;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.util.Set;

/**
 * Serves the RFC 9728 Protected Resource Metadata of a bearer-token server, so a client that was
 * refused can find the authorization server (MCP authorization § Authorization Server Discovery). The
 * document is rendered once, when the server is built, and every answer is a retained duplicate of the
 * same buffer.
 *
 * <p>It answers {@code GET} and {@code HEAD} (RFC 9110 §9.1: a general-purpose server supports both) on
 * the paths of {@link ProtectedResourceMetadata#paths}, with no authentication, because a client needs
 * it before it has a token. {@code HEAD} gets the headers of the {@code GET}, {@code Content-Length}
 * included, and no body. It sits behind the DNS-rebinding guard, so an origin that guard refuses is
 * refused here too, and behind {@code cors}: a CORS preflight to these paths is answered there, by the
 * server's one CORS policy, because {@code mcp-endpoint} lets these paths through. It is ahead of
 * {@code authentication} and of every handler that assumes an MCP request. The response carries the
 * request's CORS decision, so a browser client can read it. Any other method is {@code 405} with
 * {@code Allow: GET, HEAD} (RFC 9110 §15.5.6) and the connection closes. Everything else passes
 * untouched: the check for the {@code /.well-known/} prefix comes first and allocates nothing, so an
 * MCP request pays one string comparison.
 *
 * <p>Installed only when bearer tokens are configured. Stateless, so one instance serves every channel.
 */
@Sharable
@InternalApi
public final class ProtectedResourceMetadataHandler extends ChannelInboundHandlerAdapter {

    private static final String WELL_KNOWN_PREFIX = "/.well-known/";
    private static final String ALLOWED_METHODS = "GET, HEAD";

    private final Set<String> paths;
    private final ByteBuf document;

    public ProtectedResourceMetadataHandler(BearerConfig bearer) {
        this.paths = ProtectedResourceMetadata.paths(bearer.resource());
        this.document = Unpooled.wrappedBuffer(ProtectedResourceMetadata.json(bearer));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpRequest request)
                || !request.uri().startsWith(WELL_KNOWN_PREFIX)
                || !paths.contains(EndpointValidatorHandler.normalizedPath(request.uri()))) {
            ctx.fireChannelRead(msg);
            return;
        }
        final var cors = TachyonCorsHandler.decide(ctx, request);
        final var head = request.method() == HttpMethod.HEAD;
        if (request.method() != HttpMethod.GET && !head) {
            final var refusal = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED, Unpooled.EMPTY_BUFFER);
            refusal.headers().set(HttpHeaderNames.ALLOW, ALLOWED_METHODS);
            refusal.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            cors.applyTo(refusal);
            HttpUtil.setKeepAlive(refusal, false);
            ChannelHandlerUtils.markRejected(ctx, request);
            ctx.writeAndFlush(refusal);
            return;
        }
        final var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                head ? Unpooled.EMPTY_BUFFER : document.retainedDuplicate());
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .setInt(HttpHeaderNames.CONTENT_LENGTH, document.readableBytes());
        cors.applyTo(response);
        HttpUtil.setKeepAlive(response, HttpUtil.isKeepAlive(request));
        ReferenceCountUtil.release(request);
        ctx.writeAndFlush(response);
    }
}
