/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.rejectAndClose;
import static io.netty.util.AsciiString.contentEqualsIgnoreCase;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AsciiString;

/**
 * Rejects a repeated field line on an MCP header that carries exactly one value, with
 * {@code 400 Bad Request}.
 *
 * <p>A duplicate lets the HTTP view of a request and the executed view disagree: Netty's
 * {@code HttpHeaders#get} takes the first value, while an intermediary taking the last one sees
 * something else. For {@code MCP-Protocol-Version} that disagreement is a downgrade — the server
 * negotiates the first value and skips the newer revision's validation while the proxy believes the
 * newer revision applied. For the SEP-2243 mirrors ({@code Mcp-Method}, {@code Mcp-Name},
 * {@code Mcp-Param-*}), whose whole purpose is letting a gateway route without parsing the body, it
 * is the gateway and the server reading different values off the same request.
 *
 * <p>Duplicates are rejected even when both values are identical — otherwise an intermediary can
 * still disagree about whether the field is singular.
 *
 * <p>Scoped to that one body-independent question, which is why it can run before
 * {@code http-aggregator}: no {@code get()} downstream is ever misled, and every HTTP method is
 * covered ({@code MCP-Session-Id} and {@code Last-Event-ID} matter on GET/DELETE too). Rejection is
 * therefore plain text rather than JSON-RPC {@code -32020}: the body carrying the id to echo hasn't
 * been assembled yet, and a duplicate field line is not an MCP-level mismatch. Same treatment
 * duplicate {@code Host}/{@code Origin} already get in {@link DnsRebindingProtectionHandler}.
 *
 * <p>Whether a mirror's <em>value</em> agrees with the body is a different question, unanswerable
 * until the body exists: {@link McpHeaderMatchHandler} answers it post-aggregation, on every
 * protocol version.
 */
@Sharable
@InternalApi
public final class McpHeaderGuardHandler extends ChannelInboundHandlerAdapter {

    /** Shared instance: the handler is stateless. */
    public static final McpHeaderGuardHandler INSTANCE = new McpHeaderGuardHandler();

    private static final AsciiString PROTOCOL_VERSION = AsciiString.cached(McpHeaderNames.MCP_PROTOCOL_VERSION);
    private static final AsciiString SESSION_ID = AsciiString.cached(McpHeaderNames.MCP_SESSION_ID);
    private static final AsciiString METHOD = AsciiString.cached(McpHeaderNames.MCP_METHOD);
    private static final AsciiString NAME = AsciiString.cached(McpHeaderNames.MCP_NAME);
    private static final AsciiString LAST_EVENT_ID = AsciiString.cached(McpHeaderNames.LAST_EVENT_ID);

    private static final String DUPLICATE_MESSAGE = "Duplicate MCP header";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req && hasDuplicateSingleton(req.headers())) {
            // The offending header name is deliberately not echoed: it is client-controlled input.
            rejectAndClose(
                    ctx, msg, HttpResponseStatus.BAD_REQUEST, DUPLICATE_MESSAGE, TachyonCorsHandler.decide(ctx, req));
            return;
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * {@code names()} keeps case variants as distinct entries while {@code getAll} matches
     * case-insensitively, so {@code Mcp-Method} plus {@code mcp-method} still collapses into a
     * duplicate.
     */
    private static boolean hasDuplicateSingleton(HttpHeaders headers) {
        for (var name : headers.names()) {
            if (isSingleton(name) && headers.getAll(name).size() > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * MCP headers that carry exactly one value on any protocol version: the transport singletons,
     * plus the SEP-2243 mirrors, each of which mirrors a single body value.
     */
    private static boolean isSingleton(CharSequence name) {
        return contentEqualsIgnoreCase(name, PROTOCOL_VERSION)
                || contentEqualsIgnoreCase(name, SESSION_ID)
                || contentEqualsIgnoreCase(name, LAST_EVENT_ID)
                || contentEqualsIgnoreCase(name, METHOD)
                || contentEqualsIgnoreCase(name, NAME)
                || McpHeaderNames.isParamHeader(name);
    }
}
