/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.markRejected;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendResponseAndClose;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces MCP Accept-header rules per spec (`MUST include application/json, text/event-stream`
 * on POST; `MUST include text/event-stream` on GET). Returns 406 Not Acceptable on violation.
 * Runs after {@link EndpointValidatorHandler}, so every request it sees targets the MCP endpoint.
 */
@Sharable
@InternalApi
public final class AcceptValidationHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AcceptValidationHandler.class);

    /** Shared stateless instance. */
    public static final AcceptValidationHandler INSTANCE = new AcceptValidationHandler();

    private AcceptValidationHandler() {}

    private static final String APPLICATION_JSON = HttpHeaderValues.APPLICATION_JSON.toString();
    private static final String TEXT_EVENT_STREAM = HttpHeaderValues.TEXT_EVENT_STREAM.toString();
    private static final String TEXT_PLAIN = HttpHeaderValues.TEXT_PLAIN.toString();

    static final String[] POST_ACCEPT_TYPES = {APPLICATION_JSON, TEXT_EVENT_STREAM};
    static final String[] GET_ACCEPT_TYPES = {TEXT_EVENT_STREAM};

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req) {
            var method = req.method();
            if (method == HttpMethod.POST) {
                if (!isAcceptable(req, POST_ACCEPT_TYPES)) {
                    reject(ctx, req, "POST", POST_ACCEPT_TYPES);
                    return;
                }
            } else if (method == HttpMethod.GET) {
                if (!isAcceptable(req, GET_ACCEPT_TYPES)) {
                    reject(ctx, req, "GET", GET_ACCEPT_TYPES);
                    return;
                }
            }
        }
        ctx.fireChannelRead(msg);
    }

    private static boolean isAcceptable(HttpRequest req, String[] requiredTypes) {
        var accept = req.headers().get(HttpHeaderNames.ACCEPT);
        if (accept == null || accept.isBlank()) return false;
        // Spec requires ALL listed types (POST must accept both application/json and
        // text/event-stream, since the server chooses either for the response).
        for (var required : requiredTypes) {
            if (!accepts(accept, required)) return false;
        }
        return true;
    }

    /** Returns true if any media range in {@code accept} matches {@code requiredType} with q != 0. */
    private static boolean accepts(String accept, String requiredType) {
        var requiredMainType = requiredType.substring(0, requiredType.indexOf('/'));
        for (var range : accept.split(",")) {
            if (matches(range.trim(), requiredType, requiredMainType)) return true;
        }
        return false;
    }

    private static boolean matches(String range, String requiredType, String requiredMainType) {
        if (range.isEmpty()) return false;
        var params = range.split(";");
        var mediaRange = params[0].trim();
        // A q=0 entry explicitly rejects the media range, even a wildcard one.
        for (int i = 1; i < params.length; i++) {
            var param = params[i].trim();
            if (param.regionMatches(true, 0, "q=", 0, 2)) {
                try {
                    if (Double.parseDouble(param.substring(2).trim()) == 0.0) return false;
                } catch (NumberFormatException ignored) {
                    // Malformed q-value: treat as present (q defaults to 1).
                }
            }
        }
        if (mediaRange.equals("*/*") || mediaRange.equalsIgnoreCase(requiredType)) return true;
        var slash = mediaRange.indexOf('/');
        if (slash < 0) return false;
        var rangeSub = mediaRange.substring(slash + 1);
        return rangeSub.equals("*")
                && slash == requiredMainType.length()
                && mediaRange.regionMatches(true, 0, requiredMainType, 0, slash);
    }

    private static void reject(ChannelHandlerContext ctx, HttpRequest req, String method, String[] requiredTypes) {
        logger.debug(
                "MCP client {} Accept header on {}; required '{}'",
                req.headers().get(HttpHeaderNames.ACCEPT) == null ? "missing" : "incompatible",
                method,
                String.join(", ", requiredTypes));
        var text = "Accept header must include " + String.join(" or ", requiredTypes) + " on " + method;
        var body = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        // Read the origin before marking rejected: markRejected releases the request.
        var origin = req.headers().get(HttpHeaderNames.ORIGIN);
        markRejected(ctx, req);
        sendResponseAndClose(ctx, HttpResponseStatus.NOT_ACCEPTABLE, TEXT_PLAIN, body, origin);
    }
}
