/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.handlers;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionRegistry;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionStreamFailedException;
import dev.tachyonmcp.core.server.features.tasks.TasksExtension;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles MCP 2026-07-28's {@code subscriptions/listen} (replaces {@code resources/subscribe} and
 * the plain HTTP GET stream, SEP-2575): acknowledges the subscription on its request-scoped SSE
 * stream, registers it with {@link SubscriptionRegistry}, and defers the JSON-RPC response until
 * the client disconnects (no response) or the server shuts down (graceful {@code
 * resultType: "complete"} response) — see {@link SubscriptionRegistry#closeAll()}.
 */
@InternalApi
public final class SubscriptionsListenHandler
        implements RpcMethodHandler<ProtocolRequestMapper.SubscriptionListenRequest, Object> {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionsListenHandler.class);

    private final SubscriptionRegistry registry;

    public SubscriptionsListenHandler(SubscriptionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String method() {
        return "subscriptions/listen";
    }

    @Override
    public ProtocolRequestMapper.SubscriptionListenRequest decode(DispatchContext context, @Nullable Object rawParams) {
        var requestMapper = context.requestMapper();
        if (!requestMapper.supportsSubscriptionsListen()) {
            throw new RequestMappingException(ServerErrors.methodNotFound("Method not found"));
        }
        var request = requestMapper.subscriptionsListen(rawParams);
        if (!request.taskIds().isEmpty()) {
            var missingCapability = TasksExtension.requireDeclared(context);
            if (missingCapability != null) {
                throw new RequestMappingException(missingCapability);
            }
        }
        return request;
    }

    @Override
    public Object handle(DispatchContext context, ProtocolRequestMapper.SubscriptionListenRequest filter) {
        throw new UnsupportedOperationException("subscriptions/listen is stream-based; see handleAsync");
    }

    @Override
    public CompletionStage<Object> handleAsync(
            DispatchContext context, ProtocolRequestMapper.SubscriptionListenRequest filter) {
        var stream = context.outboundStream();
        if (stream == null) {
            return CompletableFuture.completedFuture(ServerErrors.internalError("No SSE stream available"));
        }
        var subscriptionId = context.requestId();
        if (subscriptionId == null) {
            throw new IllegalStateException("subscriptions/listen dispatched without a request id");
        }

        var pending = new CompletableFuture<>();
        var key = registry.activate(subscriptionId, stream, filter, context.responseMapper(), pending);
        // The returned future — and with it, the Observation the generic dispatch-completion path
        // drives — spans the SSE stream's whole lifetime, resolving only on disconnect, a genuine
        // transport failure, or server shutdown. establishmentNanos marks just the ack, so a
        // duration-recording listener isn't forced to measure that whole lifetime too. Set from
        // start()'s completion stage, once the ack is actually written and flushed, rather than
        // right after start() returns — start() only schedules that write and may return long before
        // it lands, especially when called off the channel's event loop.
        Consumer<@Nullable Throwable> settle = cause -> {
            registry.remove(key);
            executeCompletion(context, () -> {
                if (cause == null) {
                    pending.cancel(false);
                } else {
                    pending.completeExceptionally(new SubscriptionStreamFailedException(cause));
                }
            });
        };
        stream.start().whenComplete((ignored, failure) -> {
            if (failure == null) {
                if (context.observation().active()) {
                    context.observation().info().establishmentNanos(System.nanoTime());
                }
            } else {
                logger.debug("subscriptions/listen ack failed to flush: subscriptionId={}", subscriptionId, failure);
                // onClose may never fire (default no-op); a closed channel is an ordinary disconnect.
                settle.accept(failure instanceof ClosedChannelException ? null : failure);
            }
        });
        stream.onClose(cause -> {
            settle.accept(cause);
            logger.debug(
                    "subscriptions/listen stream ended: subscriptionId={}, failed={}", subscriptionId, cause != null);
        });
        return pending;
    }

    private static void executeCompletion(DispatchContext context, Runnable task) {
        try {
            context.engine().executor().execute(task);
        } catch (RejectedExecutionException e) {
            Thread.ofVirtual().name("tachyon-subscription-completion").start(task);
        }
    }
}
