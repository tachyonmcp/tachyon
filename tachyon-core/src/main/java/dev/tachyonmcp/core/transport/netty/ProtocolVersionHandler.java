/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import java.util.Comparator;
import java.util.List;

/**
 * Negotiates the protocol for each MCP POST and binds it to the interaction context. A request
 * naming an unsupported protocol version is flagged via {@link #UNSUPPORTED_VERSION_KEY} instead
 * of being rejected here: the JSON-RPC {@code id} the error response must echo lives in the body,
 * which {@code http-aggregator} hasn't assembled yet at this point in the pipeline. See {@link
 * UnsupportedProtocolVersionHandler}, which runs after aggregation and does the actual rejection.
 */
@Sharable
public class ProtocolVersionHandler extends ChannelInboundHandlerAdapter {

    static final AttributeKey<String> UNSUPPORTED_VERSION_KEY = AttributeKey.valueOf("unsupportedProtocolVersion");

    private final String mcpEndpoint;
    private final boolean stateless;
    static final Protocol LATEST_PROTOCOL;
    static final List<String> SUPPORTED_VERSIONS;

    static {
        LATEST_PROTOCOL = Protocols.list().stream()
                .max(Comparator.comparing(Protocol::versionString).thenComparingInt(Protocol::priority))
                .orElseThrow();
        SUPPORTED_VERSIONS = Protocols.list().stream()
                .map(Protocol::versionString)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    /** Creates a protocol binder for a server with session support. */
    public ProtocolVersionHandler(String mcpEndpoint) {
        this(mcpEndpoint, false);
    }

    /**
     * Creates a protocol binder, isolating every POST when the server is stateless.
     *
     * @param mcpEndpoint the MCP endpoint path
     * @param stateless whether server sessions are disabled
     */
    public ProtocolVersionHandler(String mcpEndpoint, boolean stateless) {
        this.mcpEndpoint = mcpEndpoint;
        this.stateless = stateless;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req
                && req.method() == HttpMethod.POST
                && req.uri().startsWith(mcpEndpoint)) {
            var protoVersion = req.headers().get(McpHeaderNames.MCP_PROTOCOL_VERSION);
            var protocol = Protocols.resolve(req);
            if (protocol.isEmpty()) {
                ctx.channel().attr(UNSUPPORTED_VERSION_KEY).set(protoVersion != null ? protoVersion : "");
            } else {
                var interaction = ctx.channel().attr(InteractionHandler.INTERACTION_CONTEXT_KEY);
                var current = interaction.get();
                if (stateless
                        || McpProtocol.VERSION.equals(protocol.get().versionString())
                        || current == null
                        || !protocol.get().versionString().equals(current.protocolVersion())) {
                    interaction.set(protocol.get().createInteractionContext());
                }
            }
        }
        ctx.fireChannelRead(msg);
    }
}
