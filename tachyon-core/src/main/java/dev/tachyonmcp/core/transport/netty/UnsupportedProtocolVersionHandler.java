/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendResponseAndClose;
import static dev.tachyonmcp.core.transport.netty.ProtocolVersionHandler.LATEST_PROTOCOL;
import static dev.tachyonmcp.core.transport.netty.ProtocolVersionHandler.SUPPORTED_VERSIONS;
import static dev.tachyonmcp.core.transport.netty.ProtocolVersionHandler.UNSUPPORTED_VERSION_KEY;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Rejects requests {@link ProtocolVersionHandler} flagged as naming an unsupported protocol
 * version, once {@code http-aggregator} has assembled the body, so the JSON-RPC {@code id} the
 * UnsupportedProtocolVersionError echoes back can be read from it (SEP-2575: error responses MUST
 * carry the request's id).
 */
@Sharable
public class UnsupportedProtocolVersionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        var requestedVersion = ctx.channel().attr(UNSUPPORTED_VERSION_KEY).getAndSet(null);
        if (requestedVersion == null || !(msg instanceof HttpRequest req)) {
            ctx.fireChannelRead(msg);
            return;
        }

        var error = LATEST_PROTOCOL
                .responseMapper()
                .error(ServerErrors.unsupportedProtocolVersion(
                        "Unsupported protocol version",
                        Map.of("supported", SUPPORTED_VERSIONS, "requested", requestedVersion)));
        var body = JsonRpcCodec.serializeError(extractId(req), error.code(), error.message(), error.data());
        sendResponseAndClose(ctx, HttpResponseStatus.BAD_REQUEST, "application/json", body);
    }

    private static @Nullable RequestId extractId(HttpRequest req) {
        if (req instanceof FullHttpRequest fullHttpRequest) {
            try {
                if (JsonRpcCodec.parseRequest(fullHttpRequest.content().duplicate())
                        instanceof JsonRpcMessage.Request<?> request) {
                    return request.id();
                }
            } catch (RuntimeException ignored) {
                // Malformed body: id is genuinely unknowable. JSON-RPC 2.0 requires null in this case.
            } finally {
                fullHttpRequest.release();
            }
        }
        return null;
    }
}
