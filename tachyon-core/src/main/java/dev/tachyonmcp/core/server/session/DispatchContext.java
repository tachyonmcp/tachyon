/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.observability.Observation;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@InternalApi
public interface DispatchContext extends ChannelContext {

    /** Returns the owning {@link ServerEngine}. */
    ServerEngine engine();

    @Override
    default <E extends ServerExtension> Optional<E> extension(Class<E> type) {
        return engine().extension(type);
    }

    /** Sets the logging level for the current session; no-op when no session is bound. */
    void setLoggingLevel(LoggingLevel level);

    /** Returns the logging level for the current session, or {@code null} when unset or session-less. */
    @Nullable
    LoggingLevel getLoggingLevel();

    /**
     * Sets the log level this specific request permits (from {@code _meta.../logLevel}, protocols
     * without sessions). Unlike {@link #setLoggingLevel}, this is request-scoped, not session-scoped.
     */
    void setPermittedLogLevel(@Nullable LoggingLevel level);

    /** Returns the log level this specific request permits, or {@code null} if it set none. */
    @Nullable
    LoggingLevel getPermittedLogLevel();

    /**
     * Returns the id of the request currently being dispatched, or {@code null} for dispatches with
     * no request id (notifications, stateless helper contexts).
     */
    @Nullable
    RequestId requestId();

    /** Returns the protocol response mapper for the current protocol version. */
    ProtocolResponseMapper responseMapper();

    /** Returns the protocol request mapper for the current protocol version. */
    ProtocolRequestMapper requestMapper();

    /** Returns the outbound SSE stream, or {@code null} if not yet upgraded. */
    @Nullable
    OutboundSseStream outboundStream();

    /** Sets the outbound SSE stream for this dispatch. */
    void setOutboundStream(@Nullable OutboundSseStream stream);

    /** Returns the observation accumulator for the operation being dispatched ({@link Observation#NONE} when disabled). */
    Observation observation();

    /**
     * Captures {@code cause} onto the current operation, gated by the server's opt-in
     * exception-detail capture policy -- for a feature handler (tool/resource/prompt/completion)
     * converting a thrown exception into a {@code ServerError} value rather than letting it
     * propagate, this is the only way that exception reaches a {@code HandlerFailed} outcome.
     */
    default void captureExceptionCause(Throwable cause) {
        var observation = observation();
        if (observation.active()
                && engine().config().observability().payloadCapture().exceptionDetail()) {
            observation.info().exceptionCause(cause);
        }
    }
}
