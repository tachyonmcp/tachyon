/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport;

import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.core.transport.netty.PeekedBody;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Validates every 2026-07-28 POST request against the requirements that only apply once a request
 * is self-describing (no protocol-level session): the required {@code _meta} fields (SEP-2575),
 * their agreement with the {@code MCP-Protocol-Version} header, and rejection of methods the
 * revision removed. Runs after {@code http-aggregator} (needs the parsed body) and before the
 * initialize/operation phase handlers, so a rejected request never reaches dispatch.
 *
 * <p>The SEP-2243 header mirrors are someone else's business: agreement with the body is
 * {@code transport.netty.http.McpHeaderMatchHandler} (every version, runs before this), presence is
 * {@link RequiredHeadersHandler} (this revision, runs after this).
 *
 * <p>One instance per server, added to every channel's pipeline unconditionally and no-ops for any
 * request that didn't negotiate 2026-07-28.
 */
@Sharable
public final class RequestValidationHandler extends ChannelInboundHandlerAdapter {

    private static final Set<String> REMOVED_METHODS =
            Set.of("initialize", "ping", "logging/setLevel", "resources/subscribe", "resources/unsubscribe");

    private static final String META = "_meta";
    private static final String PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_INFO_KEY = "io.modelcontextprotocol/clientInfo";
    private static final String CLIENT_CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";

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
        if (message == null) {
            ctx.fireChannelRead(msg);
            return;
        }
        // A notification carries no _meta requirement of its own and names no removed method.
        if (!(message instanceof JsonRpcMessage.Request<?> request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        var rejection = validate(req, request);
        if (rejection == null) {
            ctx.fireChannelRead(msg);
            return;
        }
        ChannelHandlerUtils.rejectWithServerError(ctx, req, request.id(), rejection);
    }

    private static @Nullable ServerError validate(FullHttpRequest req, JsonRpcMessage.Request<?> request) {
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
        return null;
    }

    private static @Nullable String strip(@Nullable String value) {
        return value == null ? null : value.strip();
    }
}
