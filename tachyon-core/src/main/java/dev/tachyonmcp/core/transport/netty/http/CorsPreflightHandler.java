/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Applies the request's CORS decision to synchronous preflight responses and grants the SEP-2243
 * {@code Mcp-Param-*} request headers a preflight asks for, by name.
 *
 * <p>{@code Mcp-Param-*} names depend on the tool, so the static CORS configuration cannot list
 * them, and a wildcard {@code *} would grant every other header too. This handler appends only the
 * requested names that start with {@link McpHeaderNames#MCP_PARAM_PREFIX}, have a non-empty suffix
 * and consist of RFC 9110 token characters, and only to a preflight response that already grants the
 * origin.
 *
 * <p>Install it directly before Netty's {@code CorsHandler}: that handler answers a preflight
 * synchronously within {@code channelRead}, so the response passes through {@link #write} while the
 * requested names and immutable decision are held only for that call. Origin normalization for
 * Netty is also scoped to that call. No asynchronous response reads this state. One instance per channel.
 */
@InternalApi
public final class CorsPreflightHandler extends ChannelDuplexHandler {

    private @Nullable List<String> requestedParams;
    private @Nullable CorsDecision decision;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpRequest request)
                || !isPreflight(request)
                || ctx.pipeline().get(TachyonCorsHandler.class) == null) {
            ctx.fireChannelRead(msg);
            return;
        }
        final var origin = request.headers().get(HttpHeaderNames.ORIGIN);
        final var canonical = Origins.canonical(origin);
        decision = TachyonCorsHandler.decide(ctx, request);
        requestedParams = requestedParamHeaders(request);
        // Netty matches exact strings; the decision keeps the original value for the response.
        if (canonical != null) {
            request.headers().set(HttpHeaderNames.ORIGIN, canonical);
        }
        try {
            ctx.fireChannelRead(msg);
        } finally {
            request.headers().set(HttpHeaderNames.ORIGIN, origin);
            requestedParams = null;
            decision = null;
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        final var cors = decision;
        if (cors != null && msg instanceof HttpResponse response) {
            final var headers = response.headers();
            headers.remove(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN);
            headers.remove(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS);
            headers.remove(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS);
            cors.applyTo(response);
            final var params = requestedParams;
            if (cors.allowOrigin() != null && params != null && !params.isEmpty()) {
                headers.add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, params);
            }
        }
        ctx.write(msg, promise);
    }

    private static boolean isPreflight(HttpRequest request) {
        final var headers = request.headers();
        return HttpMethod.OPTIONS.equals(request.method())
                && headers.contains(HttpHeaderNames.ORIGIN)
                && headers.contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
    }

    private static List<String> requestedParamHeaders(HttpRequest request) {
        final var params = new ArrayList<String>();
        for (var value : request.headers().getAll(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS)) {
            for (var name : value.split(",")) {
                final var trimmed = name.strip();
                if (trimmed.length() > McpHeaderNames.MCP_PARAM_PREFIX.length()
                        && McpHeaderNames.isParamHeader(trimmed)
                        && isToken(trimmed)) {
                    params.add(trimmed);
                }
            }
        }
        return params;
    }

    /** RFC 9110 {@code token}: one or more {@code tchar}. */
    private static boolean isToken(String name) {
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean tchar = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tchar) {
                return false;
            }
        }
        return true;
    }
}
