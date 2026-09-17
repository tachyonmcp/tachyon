/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.AttributeKey;
import org.jspecify.annotations.Nullable;

/**
 * One JSON parse per POST body, shared by every handler that needs to look inside it.
 *
 * <p>Several validation handlers must read the body before dispatch — the 2026-07-28 {@code _meta}
 * checks, extension negotiation, and SEP-2243 mirror validation — and each one used to parse
 * {@code req.content().duplicate()} on its own, so a single request was parsed up to four times.
 * The parse outcome is cached on a channel attribute here instead, and the handlers downstream of
 * the peek reuse it.
 *
 * <p>The cache entry carries the {@link FullHttpRequest} it was produced from and is only ever
 * served back for that same instance: a keep-alive channel must never hand request N's parse to
 * request N+1. All access is on the event loop, so no synchronization is needed.
 */
@InternalApi
public final class PeekedBody {

    private static final AttributeKey<Parsed> PEEKED_BODY = AttributeKey.valueOf("tachyonPeekedBody");

    private PeekedBody() {}

    /**
     * A body parse, tied to the request it came from.
     *
     * @param request the request whose body was parsed
     * @param parse the parse outcome, malformed bodies included
     */
    @InternalApi
    public record Parsed(FullHttpRequest request, JsonRpcCodec.Parse parse) {}

    /**
     * Returns the parsed body of {@code req}, parsing it at most once per request. A malformed body
     * is cached as {@code null}, so a second peek neither re-parses nor re-throws.
     *
     * @param ctx the channel handler context
     * @param req the aggregated request to peek into
     * @return the parsed message, or {@code null} when the body is not a valid JSON-RPC message
     */
    public static @Nullable JsonRpcMessage peek(ChannelHandlerContext ctx, FullHttpRequest req) {
        var attr = ctx.channel().attr(PEEKED_BODY);
        var existing = attr.get();
        if (existing != null && existing.request() == req) {
            return existing.parse().message();
        }
        // A duplicate view shares the backing memory but has its own reader index, so peeking
        // here doesn't disturb what the operation/init handler reads from req.content() next.
        var parse = JsonRpcCodec.tryParseRequest(req.content().duplicate());
        attr.set(new Parsed(req, parse));
        return parse.message();
    }

    /**
     * Consumes the cached parse of {@code req}, clearing the attribute. A {@code null} return means
     * no peek ran for this request and the caller must parse the body itself; a non-null holder
     * whose {@link JsonRpcCodec.Parse#message()} is {@code null} means a peek ran and the body was
     * malformed — the two cases a plain {@code JsonRpcMessage} return could not tell apart.
     *
     * <p>Call on the event loop, before any async hop: a pipelined next request must not be able to
     * overwrite the entry between the hop and the read.
     *
     * @param ctx the channel handler context
     * @param req the aggregated request being dispatched
     * @return the cached parse, or {@code null} when none was made for this request
     */
    public static @Nullable Parsed cached(ChannelHandlerContext ctx, FullHttpRequest req) {
        var attr = ctx.channel().attr(PEEKED_BODY);
        var existing = attr.getAndSet(null);
        return existing != null && existing.request() == req ? existing : null;
    }

    /**
     * Drops any cached parse on this channel, so a rejected request is not held past its own
     * lifetime.
     *
     * @param ctx the channel handler context
     */
    public static void clear(ChannelHandlerContext ctx) {
        ctx.channel().attr(PEEKED_BODY).set(null);
    }
}
