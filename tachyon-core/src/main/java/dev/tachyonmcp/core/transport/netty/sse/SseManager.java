/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.core.transport.netty.http.CorsDecision;
import dev.tachyonmcp.core.transport.netty.http.HttpHelpers;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages SSE stream lifecycle for GET requests: opens stateful or stateless
 * streams via {@link NettySseConnection}, writes opening frames and priming
 * events, and replays missed events on reconnection.
 */
@InternalApi
public class SseManager {

    private static final Logger logger = LoggerFactory.getLogger(SseManager.class);

    static final int SSE_RETRY_DELAY_MS = 3000;

    private final ServerEngine server;

    public SseManager(ServerEngine server) {
        this.server = server;
    }

    public void openStream(
            ChannelHandlerContext ctx, Session session, @Nullable String lastEventId, CorsDecision cors) {
        var holder = new NettySseConnection[1];
        var connection = new NettySseConnection(ctx.channel(), () -> {
            // Only reset the session if THIS connection is still the current one. A reconnect may
            // have already replaced it; wiping to NOOP here would orphan the newer channel.
            if (session.clearConnection(holder[0])) {
                session.touch();
                logger.debug("SSE connection closed for session={}", session.id());
            }
        });
        holder[0] = connection;
        session.connection(connection);
        ChannelHandlerUtils.setSession(ctx, session);

        writeOpeningFrames(ctx, cors, connection);

        if (lastEventId != null && !lastEventId.isEmpty()) {
            var hash = lastEventId.indexOf('#');
            // Remember which POST-SSE stream (if any) this connection is resuming, so a response
            // finalized after the replay window can still be delivered live on this stream.
            session.resumingStreamKey(hash < 0 ? null : lastEventId.substring(hash + 1));
            server.executor().execute(() -> replayEvents(session, lastEventId));
        } else {
            session.resumingStreamKey(null);
        }

        logger.debug("SSE stream opened for session={}", session.id());
    }

    public void openStatelessStream(ChannelHandlerContext ctx, CorsDecision cors) {
        var connection = new NettySseConnection(
                ctx.channel(),
                () -> logger.debug(
                        "Stateless SSE connection closed: {}", ctx.channel().remoteAddress()));

        writeOpeningFrames(ctx, cors, connection);

        logger.debug("Stateless SSE stream opened: {}", ctx.channel().remoteAddress());
    }

    private void writeOpeningFrames(ChannelHandlerContext ctx, CorsDecision cors, NettySseConnection connection) {
        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpHelpers.setSseStreamHeaders(response, cors);
        ctx.write(response);
        ctx.writeAndFlush(
                new DefaultHttpContent(ByteBufUtil.writeUtf8(ctx.alloc(), "retry: " + SSE_RETRY_DELAY_MS + "\n")));
        SseHeartbeat.enable(ctx.channel(), server.config().network().heartbeatInterval());
        var primeSse = new SseEvent(String.valueOf(server.nextEventId()), "message", "");
        connection.send(primeSse);
    }

    /**
     * Replays the missed events of ONE stream, identified by the {@code Last-Event-ID}: a plain
     * numeric id resumes the session's GET stream, {@code <n>#<key>} resumes the POST-SSE stream
     * with that key. Events of other streams are never replayed (MCP Streamable HTTP: "the server
     * MUST NOT replay messages that would have been sent on a different stream").
     */
    void replayEvents(Session session, String lastEventId) {
        try {
            var hash = lastEventId.indexOf('#');
            var lastSseId = Long.parseLong(hash < 0 ? lastEventId : lastEventId.substring(0, hash));
            var targetStreamKey = hash < 0 ? null : lastEventId.substring(hash + 1);
            var replayed = server.replay(session.id(), -1);
            for (var event : replayed) {
                var sseId = event.sseEventId();
                if (sseId < 0 || sseId <= lastSseId) continue;
                if (!java.util.Objects.equals(event.streamKey(), targetStreamKey)) continue;
                var sseEvent = ServerEngine.toSseEvent(event);
                if (sseEvent == null) continue;
                if (!session.send(sseEvent)) break; // session closed or throttled mid-replay
            }
        } catch (NumberFormatException e) {
            logger.warn("Invalid Last-Event-ID: {}", lastEventId);
        }
    }
}
