/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport;

import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.MirroredArgument;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.core.transport.netty.PeekedBody;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import org.jspecify.annotations.Nullable;

/**
 * Demands the SEP-2243 header mirrors of every 2026-07-28 POST request — this is the revision that
 * made them mandatory. {@code Mcp-Method} always (requests and notifications alike); {@code Mcp-Name}
 * when the method addresses a named target; {@code Mcp-Param-*} for every {@code x-mcp-header}
 * property the call passed a mirrorable value for.
 *
 * <p>Only <em>presence</em> is asked here. Whether a mirror that is present agrees with the body is
 * a rule every revision owes a gateway, settled earlier in the pipeline by
 * {@code transport.netty.http.McpHeaderMatchHandler}; by the time a request reaches this handler,
 * every mirror it carries is already known to be truthful.
 *
 * <p>One instance per server (constructed with that server's {@link ServerEngine} to resolve
 * {@code x-mcp-header} tool-schema annotations), added to every channel's pipeline unconditionally
 * and no-ops for any request that didn't negotiate 2026-07-28. Runs after {@code http-aggregator}
 * (needs the parsed body) and before the initialize/operation phase handlers, so a rejected request
 * never reaches dispatch.
 */
@Sharable
public final class RequiredHeadersHandler extends ChannelInboundHandlerAdapter {

    private final ServerEngine server;

    public RequiredHeadersHandler(ServerEngine server) {
        this.server = server;
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

        // Malformed JSON parses to null: let the normal parse-error path downstream handle it.
        var message = PeekedBody.peek(ctx, req);
        ServerError rejection;
        switch (message) {
            // A notification mirrors only its method, and has no id to echo when the mirror is missing.
            case JsonRpcMessage.Notification<?> ignored -> {
                rejection = requireMethodMirror(req);
                if (rejection != null) {
                    ChannelHandlerUtils.rejectWithServerError(ctx, req, null, rejection);
                    return;
                }
            }
            case JsonRpcMessage.Request<?> request -> {
                rejection = requireMirrors(req, request);
                if (rejection != null) {
                    ChannelHandlerUtils.rejectWithServerError(ctx, req, request.id(), rejection);
                    return;
                }
            }
            case null, default -> {}
        }
        ctx.fireChannelRead(msg);
    }

    private @Nullable ServerError requireMirrors(FullHttpRequest req, JsonRpcMessage.Request<?> request) {
        var methodRejection = requireMethodMirror(req);
        if (methodRejection != null) return methodRejection;

        var method = request.method();
        if (McpHeaderNames.mirroredNameField(method) != null && req.headers().get(McpHeaderNames.MCP_NAME) == null) {
            return ServerErrors.headerMismatch(
                    "Header mismatch: " + McpHeaderNames.MCP_NAME + " is required for method '" + method + "'");
        }

        var params = JsonUtils.toParamsNode(request.params());
        for (var argument : MirroredArgument.of(server.tools(), method, params)) {
            // A mirrorable value in the body and no header to route on: the gateway sees less than
            // the server executes. An unmirrorable value asks for no header in the first place.
            if (argument.bodyValue() != null && req.headers().get(argument.headerName()) == null) {
                return ServerErrors.headerMismatch("Header mismatch: " + argument.headerName()
                        + " is required because body arguments contains '" + argument.propertyName() + "'");
            }
        }
        return null;
    }

    private static @Nullable ServerError requireMethodMirror(FullHttpRequest req) {
        return req.headers().get(McpHeaderNames.MCP_METHOD) != null
                ? null
                : ServerErrors.headerMismatch(
                        "Header mismatch: " + McpHeaderNames.MCP_METHOD + " is required and must mirror body method");
    }
}
