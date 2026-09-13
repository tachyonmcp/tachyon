/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import dev.tachyonmcp.core.server.handlers.ExtensionNegotiator;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.util.List;

/**
 * Negotiates MCP extensions for 2026-07-28: this revision removed {@code initialize} (no session to
 * negotiate once and persist), so every request self-describes the extensions it wants via {@code
 * _meta."io.modelcontextprotocol/clientCapabilities".extensions} (SEP-2575). {@code
 * ProtocolVersionHandler} binds a brand-new, empty interaction context to the channel before every
 * 2026-07-28 request (see its {@code interaction.set(...)} call), so enabling extensions on it here is
 * already correctly scoped to this one request — no session or channel-level bookkeeping needed.
 *
 * <p>Delegates the actual matching/enabling to {@link ExtensionNegotiator}, shared with 2025-11-25's
 * {@code InitializeHandler} — both fire {@code ServerExtension.onConnectionInit} for a negotiated
 * extension, not just this handler's own {@code enableExtension} bookkeeping.
 *
 * <p>Per-server instance (not shared/static), added to every pipeline unconditionally and no-ops for
 * any request that didn't negotiate 2026-07-28 — see the sibling {@link RequestValidationHandler}, run
 * first so this only negotiates extensions for requests that already passed the required {@code _meta}
 * shape checks.
 */
@Sharable
@InternalApi
public final class ExtensionNegotiationHandler extends ChannelInboundHandlerAdapter {

    private final List<ServerExtension> extensions;

    public ExtensionNegotiationHandler(List<ServerExtension> extensions) {
        this.extensions = extensions;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest req) || req.method() != HttpMethod.POST) {
            ctx.fireChannelRead(msg);
            return;
        }
        var interaction = ChannelHandlerUtils.getInteractionContext(ctx);
        if (interaction == null || !McpProtocol.VERSION.equals(interaction.protocolVersion())) {
            ctx.fireChannelRead(msg);
            return;
        }

        JsonRpcMessage message;
        try {
            // A duplicate view shares the backing memory but has its own reader index, so peeking
            // here doesn't disturb what the operation handler reads from req.content() next.
            message = JsonRpcCodec.parseRequest(req.content().duplicate());
        } catch (RuntimeException e) {
            // Malformed JSON: let the normal parse-error path downstream handle it.
            ctx.fireChannelRead(msg);
            return;
        }
        if (message instanceof JsonRpcMessage.Request<?> request) {
            try {
                var declared = interaction.protocol().requestMapper().declaredExtensions(request.params());
                ExtensionNegotiator.negotiate(extensions, interaction, declared);
            } catch (RequestMappingException ignored) {
                // Invalid params: the dispatcher reports invalid_params downstream.
            }
        }
        ctx.fireChannelRead(msg);
    }
}
