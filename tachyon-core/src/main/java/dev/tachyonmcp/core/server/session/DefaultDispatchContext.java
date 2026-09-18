/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.AttributeKey;
import dev.tachyonmcp.api.runtime.ClientContext;
import dev.tachyonmcp.api.runtime.ContextNotifications;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.NotificationLogSupport;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.observability.Observation;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@InternalApi
public class DefaultDispatchContext implements DispatchContext {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDispatchContext.class);

    private final ChannelContext channel;
    private final ServerEngine server;
    private final @Nullable RequestId requestId;
    private final ContextNotifications notifications = new NotificationsImpl();
    private volatile @Nullable OutboundSseStream outboundStream;
    private volatile @Nullable LoggingLevel permittedLogLevel;
    private volatile Observation observation = Observation.NONE;

    public DefaultDispatchContext(ChannelContext channel, ServerEngine server) {
        this(channel, server, null);
    }

    public DefaultDispatchContext(ChannelContext channel, ServerEngine server, @Nullable RequestId requestId) {
        this.channel = channel;
        this.server = server;
        this.requestId = requestId;
    }

    public static DispatchContext create(Protocol protocol, ServerEngine server) {
        return new DefaultDispatchContext(protocol.createInteractionContext(), server);
    }

    public static DispatchContext stateless(ServerEngine server) {
        return new DefaultDispatchContext(Protocols.baseline().createInteractionContext(), server);
    }

    public static DispatchContext noop() {
        return NoopInteractionContext.INSTANCE;
    }

    // === channel-scoped state, delegated ===

    @Override
    public Protocol protocol() {
        return channel.protocol();
    }

    @Override
    public @Nullable Lifecycle lifecycle() {
        return channel.lifecycle();
    }

    @Override
    public void setLifecycle(Lifecycle lifecycle) {
        channel.setLifecycle(lifecycle);
    }

    @Override
    public @Nullable Session session() {
        return channel.session();
    }

    @Override
    public void setSession(@Nullable Session session) {
        if (session != null) {
            session.protocol(protocol());
        }
        channel.setSession(session);
    }

    @Override
    public <T> Optional<T> get(AttributeKey<T> key) {
        return channel.get(key);
    }

    @Override
    public <T> void set(AttributeKey<T> key, T value) {
        channel.set(key, value);
    }

    @Override
    public void enableExtension(String extensionId) {
        var s = session();
        if (s != null) {
            s.enableExtension(extensionId);
        } else {
            channel.enableExtension(extensionId);
        }
    }

    @Override
    public boolean isExtensionEnabled(String extensionId) {
        var s = session();
        return s != null ? s.isExtensionEnabled(extensionId) : channel.isExtensionEnabled(extensionId);
    }

    // === request-scoped dispatch surface ===

    @Override
    public ServerEngine engine() {
        return server;
    }

    @Override
    public void setLoggingLevel(LoggingLevel level) {
        var s = session();
        if (s != null) {
            server.setLoggingLevel(s.id(), level);
        }
    }

    @Override
    @Nullable
    public LoggingLevel getLoggingLevel() {
        var s = session();
        return s != null ? server.getLoggingLevel(s.id()) : null;
    }

    @Override
    public void setPermittedLogLevel(@Nullable LoggingLevel level) {
        this.permittedLogLevel = level;
    }

    @Override
    @Nullable
    public LoggingLevel getPermittedLogLevel() {
        return permittedLogLevel;
    }

    @Override
    @Nullable
    public RequestId requestId() {
        return requestId;
    }

    @Override
    public ContextNotifications notifications() {
        return notifications;
    }

    @Override
    public CompletableFuture<String> sendRequest(String method, Object params) {
        var s = session();
        if (s == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Server-to-client requests require a session (stateless mode)"));
        }
        return server.sendRequest(s, method, params, outboundStream);
    }

    @Override
    public ClientContext client() {
        return new WireClientContext(this);
    }

    @Override
    public @Nullable OutboundSseStream outboundStream() {
        return outboundStream;
    }

    @Override
    public void setOutboundStream(@Nullable OutboundSseStream stream) {
        this.outboundStream = stream;
    }

    @Override
    public ProtocolResponseMapper responseMapper() {
        return protocol().responseMapper();
    }

    @Override
    public ProtocolRequestMapper requestMapper() {
        return protocol().requestMapper();
    }

    @Override
    public Observation observation() {
        return observation;
    }

    /** Sets the observation accumulator for this dispatch. Only {@code McpDispatcher} calls this. */
    public void setObservation(Observation observation) {
        this.observation = observation;
    }

    private class NotificationsImpl implements ContextNotifications {

        private void send(String method, Object params) {
            var s = session();
            if (s != null) {
                server.sendNotification(s, method, params, outboundStream());
                return;
            }
            var stream = outboundStream();
            if (stream == null) {
                logger.debug("Dropping notification {}: no session and no outbound stream bound", method);
                return;
            }
            var json = JsonRpcCodec.serializeNotificationAsString(method, JsonRpcCodec.toJsonParams(params));
            stream.start();
            stream.writeEvent(
                    new SseEvent(ServerEngine.wireEventId(server.nextEventId(), stream.streamKey()), "message", json));
        }

        @Override
        public void log(LoggingLevel level, @Nullable String logger, @Nullable Object data) {
            if (shouldEmit(level)) {
                var mapper = responseMapper();
                send(
                        NotificationLogSupport.LOG_METHOD,
                        mapper.encode(mapper.loggingMessageParams(level, logger, data)));
            }
        }

        @Override
        public void progress(@Nullable ProgressToken progressToken, double progress, double total, String message) {
            if (progressToken == null) {
                logger.debug("Dropping progress notification: no progressToken (client did not opt into progress)");
                return;
            }
            var mapper = responseMapper();
            send(
                    "notifications/progress",
                    mapper.encode(mapper.progressNotificationParams(progressToken, progress, total, message)));
        }

        @Override
        public void comment(@Nullable String message) {
            var stream = outboundStream();
            if (stream == null) {
                logger.debug("Dropping SSE comment: no outbound stream bound");
                return;
            }
            stream.comment(message);
        }

        private boolean shouldEmit(LoggingLevel level) {
            if (!server.config().capabilities().logging()) return false;
            if (!protocol().supportsSessions()) {
                // No logging/setLevel RPC, no session: the client opts in per request via
                // _meta.../logLevel. Absent that, the server MUST NOT log at any level.
                var permitted = getPermittedLogLevel();
                return permitted != null && level.ordinal() >= permitted.ordinal();
            }
            var configuredLevel = getLoggingLevel();
            var threshold = configuredLevel != null ? configuredLevel : LoggingLevel.INFO;
            return level.ordinal() >= threshold.ordinal();
        }
    }
}
