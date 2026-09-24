/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.markRejected;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendResponseAndClose;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requires {@code Content-Type: application/json} (parameters such as {@code charset} allowed) on
 * every POST. Anything else, including a missing header, is answered with {@code 415 Unsupported
 * Media Type} and a JSON-RPC {@code -32600} error before the body is read, so the error carries
 * {@code "id": null}.
 *
 * <p>This makes CORS an effective gate: browsers send {@code text/plain}, form and multipart bodies
 * as "simple" requests without a preflight, so accepting them would let a page whose origin passed
 * {@link DnsRebindingProtectionHandler} invoke tools without the CORS policy ever being consulted.
 *
 * <p>Install it after {@link EndpointValidatorHandler}: it does not match the path itself, so every
 * request the endpoint validator lets through is covered, however its path is spelled.
 */
@Sharable
@InternalApi
public final class ContentTypeValidationHandler extends ChannelInboundHandlerAdapter {

    /** Shared instance; the handler is stateless. */
    public static final ContentTypeValidationHandler INSTANCE = new ContentTypeValidationHandler();

    private static final Logger logger = LoggerFactory.getLogger(ContentTypeValidationHandler.class);

    private static final String APPLICATION_JSON = HttpHeaderValues.APPLICATION_JSON.toString();

    /** JSON-RPC 2.0 "Invalid Request"; the same code in every MCP revision. */
    private static final int INVALID_REQUEST = -32600;

    private static final String MESSAGE = "Unsupported Media Type: Content-Type must be " + APPLICATION_JSON;

    private ContentTypeValidationHandler() {}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req
                && req.method() == HttpMethod.POST
                && !isJson(req.headers().get(HttpHeaderNames.CONTENT_TYPE))) {
            reject(ctx, req);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private static boolean isJson(@Nullable String contentType) {
        if (contentType == null) return false;
        final var semicolon = contentType.indexOf(';');
        final var mediaType = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return mediaType.trim().equalsIgnoreCase(APPLICATION_JSON);
    }

    private static void reject(ChannelHandlerContext ctx, HttpRequest req) {
        logger.debug(
                "MCP client POST with Content-Type '{}'; required '{}'",
                req.headers().get(HttpHeaderNames.CONTENT_TYPE),
                APPLICATION_JSON);
        final var body = JsonRpcCodec.serializeError(null, INVALID_REQUEST, MESSAGE, null);
        // Decide CORS before marking rejected: markRejected releases the request.
        var cors = TachyonCorsHandler.decide(ctx, req);
        markRejected(ctx, req);
        sendResponseAndClose(ctx, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, APPLICATION_JSON, body, cors);
    }
}
