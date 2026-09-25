/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.runtime.Session;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link OutboundStreamResolver} that diverts events through an {@link OutboundSseStream} bound in
 * the current dispatch context when the dispatch session matches the target session. Otherwise, lets
 * the caller fall through to the normal GET-SSE path.
 */
@InternalApi
public final class OutboundSseStreamMessageRouter implements OutboundStreamResolver {

    private static final Logger logger = LoggerFactory.getLogger(OutboundSseStreamMessageRouter.class);
    private static final ThreadLocal<@Nullable OutboundSseStream> OUTBOUND_SSE_STREAM = new ThreadLocal<>();
    private static final ThreadLocal<@Nullable String> DISPATCH_SESSION_ID = new ThreadLocal<>();

    public static <T> T withDispatchContext(
            @Nullable String sessionId, @Nullable OutboundSseStream outboundSseStream, Callable<T> action)
            throws Exception {
        var prevSessionId = DISPATCH_SESSION_ID.get();
        var prevStream = OUTBOUND_SSE_STREAM.get();
        DISPATCH_SESSION_ID.set(sessionId);
        OUTBOUND_SSE_STREAM.set(outboundSseStream);
        try {
            return action.call();
        } finally {
            if (prevSessionId == null) DISPATCH_SESSION_ID.remove();
            else DISPATCH_SESSION_ID.set(prevSessionId);
            if (prevStream == null) OUTBOUND_SSE_STREAM.remove();
            else OUTBOUND_SSE_STREAM.set(prevStream);
        }
    }

    public static @Nullable OutboundSseStream currentOutboundSseStream() {
        return OUTBOUND_SSE_STREAM.get();
    }

    @Override
    public @Nullable OutboundSseStream resolve(Session session) {
        var outboundStream = OUTBOUND_SSE_STREAM.get();
        logger.trace("resolve: session={}, outboundStream={}", session.id(), outboundStream);
        if (outboundStream == null) return null;
        var currentSessionId = DISPATCH_SESSION_ID.get();
        if (currentSessionId == null || !currentSessionId.equals(session.id())) {
            logger.trace("resolve sessionId mismatch: current={}, expected={}", currentSessionId, session.id());
            return null;
        }
        return outboundStream;
    }
}
