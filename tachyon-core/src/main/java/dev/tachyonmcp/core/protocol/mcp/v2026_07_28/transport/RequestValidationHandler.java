/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.MirroredArgument;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Validates every 2026-07-28 POST request against the requirements that only apply once a request
 * is self-describing (no protocol-level session): the required {@code _meta} fields (SEP-2575),
 * their agreement with the {@code MCP-Protocol-Version} header, and rejection of methods the
 * revision removed. Runs after {@code http-aggregator} (needs the parsed body) and before the
 * initialize/operation phase handlers, so a rejected request never reaches dispatch.
 *
 * <p>Also enforces that the SEP-2243 mirrors are <em>present</em> — this is the revision that made
 * them mandatory. Whether a mirror that is present <em>agrees</em> with the body is a rule every
 * revision owes a gateway, not this one's, and lives in
 * {@code transport.netty.http.McpMirrorValidationHandler}, which runs after this handler.
 *
 * <p>One instance per server (constructed with that server's {@link ServerEngine} to resolve
 * {@code x-mcp-header} tool-schema annotations), added to every channel's pipeline unconditionally
 * and no-ops for any request that didn't negotiate 2026-07-28.
 */
@Sharable
public final class RequestValidationHandler extends ChannelInboundHandlerAdapter {

    private static final Set<String> REMOVED_METHODS =
            Set.of("initialize", "ping", "logging/setLevel", "resources/subscribe", "resources/unsubscribe");

    private static final String META = "_meta";
    private static final String PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_INFO_KEY = "io.modelcontextprotocol/clientInfo";
    private static final String CLIENT_CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";

    private final ServerEngine server;

    public RequestValidationHandler(ServerEngine server) {
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

        JsonRpcMessage message;
        try {
            // A duplicate view shares the backing memory but has its own reader index, so peeking
            // here doesn't disturb what the operation/init handler reads from req.content() next.
            message = JsonRpcCodec.parseRequest(req.content().duplicate());
        } catch (RuntimeException e) {
            // Malformed JSON: let the normal parse-error path downstream handle it.
            ctx.fireChannelRead(msg);
            return;
        }
        // A notification carries no _meta requirement of its own and names no removed method, but it
        // does mirror its method — there is no id to echo when that mirror is missing.
        if (message instanceof JsonRpcMessage.Notification<?>) {
            rejectOrForward(ctx, req, null, requireMethodMirror(req));
            return;
        }
        if (!(message instanceof JsonRpcMessage.Request<?> request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        rejectOrForward(ctx, req, request.id(), validate(req, request));
    }

    private static void rejectOrForward(
            ChannelHandlerContext ctx, FullHttpRequest req, @Nullable RequestId id, @Nullable ServerError rejection) {
        if (rejection == null) {
            ctx.fireChannelRead(req);
            return;
        }
        ChannelHandlerUtils.rejectWithServerError(ctx, req, id, rejection);
    }

    private @Nullable ServerError validate(FullHttpRequest req, JsonRpcMessage.Request<?> request) {
        var method = request.method();
        if (REMOVED_METHODS.contains(method)) {
            return ServerErrors.methodNotFound("Method not found");
        }

        var params = JsonUtils.toParamsNode(request.params());
        var meta = params.get(META);
        if (meta == null || !meta.isObject()) {
            return ServerErrors.invalidParams("Missing required " + META);
        }
        var rawProtocolVersion = meta.get(PROTOCOL_VERSION_KEY);
        if (rawProtocolVersion == null || !rawProtocolVersion.isString()) {
            return ServerErrors.invalidParams(META + " missing required field: " + PROTOCOL_VERSION_KEY);
        }
        var clientInfo = meta.get(CLIENT_INFO_KEY);
        if (clientInfo != null
                && !(clientInfo.isObject()
                        && clientInfo.path("name").isString()
                        && clientInfo.path("version").isString())) {
            return ServerErrors.invalidParams(META + " invalid field: " + CLIENT_INFO_KEY);
        }
        if (!meta.path(CLIENT_CAPABILITIES_KEY).isObject()) {
            return ServerErrors.invalidParams(META + " missing required field: " + CLIENT_CAPABILITIES_KEY);
        }

        var metaProtocolVersion = rawProtocolVersion.stringValue();
        var headerProtocolVersion = strip(req.headers().get(McpHeaderNames.MCP_PROTOCOL_VERSION));
        if (!metaProtocolVersion.equals(headerProtocolVersion)) {
            return ServerErrors.headerMismatch("Header mismatch: " + McpHeaderNames.MCP_PROTOCOL_VERSION
                    + " header value '" + headerProtocolVersion + "' does not match body " + META + "."
                    + PROTOCOL_VERSION_KEY + " value '" + metaProtocolVersion + "'");
        }

        return requireMirrors(req, method, params);
    }

    /**
     * Every mirror this revision demands of the request must be present. What each one's value has
     * to be is not asked here — {@code McpMirrorValidationHandler} compares it to the body for every
     * revision, so demanding presence is all that is left version-specific.
     */
    private @Nullable ServerError requireMirrors(FullHttpRequest req, String method, JsonNode params) {
        var methodRejection = requireMethodMirror(req);
        if (methodRejection != null) return methodRejection;

        if (McpHeaderNames.mirroredNameField(method) != null && req.headers().get(McpHeaderNames.MCP_NAME) == null) {
            return ServerErrors.headerMismatch(
                    "Header mismatch: " + McpHeaderNames.MCP_NAME + " is required for method '" + method + "'");
        }

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

    private static @Nullable String strip(@Nullable String value) {
        return value == null ? null : value.strip();
    }
}
