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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        var cursor = ResumeCursor.parse(lastEventId);
        var network = server.config().network();
        var holder = new NettySseConnection[1];
        var connection = new NettySseConnection(
                ctx.channel(),
                () -> {
                    // Only reset the session if THIS connection is still the current one. A reconnect may
                    // have already replaced it; wiping to NOOP here would orphan the newer channel.
                    if (session.clearConnection(holder[0])) {
                        session.touch();
                        logger.debug("SSE connection closed for session={}", session.id());
                    }
                },
                cursor != null,
                network.sseStallTimeout(),
                network.maxPendingSseBytes());
        holder[0] = connection;
        session.connection(connection);
        ChannelHandlerUtils.setSession(ctx, session);

        if (cursor != null) {
            // Remember which POST-SSE stream (if any) this connection is resuming, so a response
            // finalized after the replay window can still be delivered live on this stream.
            session.resumingStreamKey(cursor.streamKey());
            // Prime with the client's own cursor: a fresh id would move its Last-Event-ID past the
            // not-yet-replayed backlog (and drop the stream key) if this connection drops early.
            writeOpeningFrames(ctx, cors, connection, cursor.wireId());
            server.executor().execute(() -> replay(session.id(), cursor, connection));
        } else {
            session.resumingStreamKey(null);
            writeOpeningFrames(ctx, cors, connection, String.valueOf(server.nextEventId()));
        }

        logger.debug("SSE stream opened for session={}", session.id());
    }

    public void openStatelessStream(ChannelHandlerContext ctx, CorsDecision cors) {
        var connection = new NettySseConnection(
                ctx.channel(),
                () -> logger.debug(
                        "Stateless SSE connection closed: {}", ctx.channel().remoteAddress()));

        writeOpeningFrames(ctx, cors, connection, String.valueOf(server.nextEventId()));

        logger.debug("Stateless SSE stream opened: {}", ctx.channel().remoteAddress());
    }

    private void writeOpeningFrames(
            ChannelHandlerContext ctx, CorsDecision cors, NettySseConnection connection, String primingId) {
        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpHelpers.setSseStreamHeaders(response, cors);
        ctx.write(response);
        ctx.writeAndFlush(
                new DefaultHttpContent(ByteBufUtil.writeUtf8(ctx.alloc(), "retry: " + SSE_RETRY_DELAY_MS + "\n")));
        SseHeartbeat.enable(ctx.channel(), server.config().network().heartbeatInterval());
        connection.prime(new SseEvent(primingId, "message", ""));
    }

    private void replay(String sessionId, ResumeCursor cursor, NettySseConnection connection) {
        try {
            connection.replay(missedEvents(sessionId, cursor));
        } catch (RuntimeException e) {
            // Held live events can't be released without the backlog: let the client resume again.
            logger.error("SSE replay failed for session={}", sessionId, e);
            connection.close();
        }
    }

    /**
     * Collects the missed events of ONE stream, identified by the {@code Last-Event-ID}: a plain
     * numeric id resumes the session's GET stream, {@code <n>#<key>} resumes the POST-SSE stream
     * with that key. Events of other streams are never replayed (MCP Streamable HTTP: "the server
     * MUST NOT replay messages that would have been sent on a different stream").
     */
    List<SseEvent> missedEvents(String sessionId, ResumeCursor cursor) {
        var missed = new ArrayList<SseEvent>();
        for (var event : server.replay(sessionId, -1)) {
            var sseId = event.sseEventId();
            if (sseId < 0 || sseId <= cursor.sseId()) continue;
            if (!Objects.equals(event.streamKey(), cursor.streamKey())) continue;
            var sseEvent = ServerEngine.toSseEvent(event);
            if (sseEvent != null) missed.add(sseEvent);
        }
        return missed;
    }

    /**
     * A parsed {@code Last-Event-ID}: the global event counter value and, for a POST-SSE stream,
     * its stream key.
     */
    record ResumeCursor(long sseId, @Nullable String streamKey) {

        String wireId() {
            return ServerEngine.wireEventId(sseId, streamKey);
        }

        /**
         * Parses {@code <n>} or {@code <n>#<key>}; {@code null} when absent or malformed. Both parts
         * are numeric (see {@code PostSseStream}), so the cursor is safe to echo as an SSE id.
         */
        static @Nullable ResumeCursor parse(@Nullable String lastEventId) {
            if (lastEventId == null || lastEventId.isEmpty()) return null;
            var hash = lastEventId.indexOf('#');
            try {
                var sseId = Long.parseLong(hash < 0 ? lastEventId : lastEventId.substring(0, hash));
                var streamKey = hash < 0 ? null : String.valueOf(Long.parseLong(lastEventId.substring(hash + 1)));
                return new ResumeCursor(sseId, streamKey);
            } catch (NumberFormatException e) {
                logger.warn("Invalid Last-Event-ID: {}", lastEventId);
                return null;
            }
        }
    }
}
