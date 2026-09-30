/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.subscriptions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DispatchContext;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Tracks active {@code subscriptions/listen} streams (2026-07-28), independent of any session, and
 * pushes notifications to each stream whose filter opted into the {@link SubscriptionTopic} they
 * belong to. Knows no filter names: core and extensions {@link #register} their topics and {@link
 * #publish} to them, and every pushed notification is tagged with the stream's own subscription id.
 *
 * <p>Keyed by an internal counter, not the wire {@code subscriptionId} — two different stateless
 * connections may legally reuse the same JSON-RPC request id, so the registry's own key and the id
 * echoed back to clients are kept distinct.
 */
@InternalApi
public final class SubscriptionRegistry {

    /** A live {@code subscriptions/listen} stream: its filter, transport, and deferred response. */
    private record Entry(
            RequestId subscriptionId,
            OutboundSseStream stream,
            SubscriptionFilter filter,
            ProtocolResponseMapper responseMapper,
            CompletableFuture<Object> pendingResponse) {}

    private final ServerEngine server;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong nextKey = new AtomicLong();

    /** Copy-on-write: topics are registered at startup and read on every listen and notification. */
    private volatile Map<String, SubscriptionTopic<?>> topics = Map.of();

    /**
     * Guards {@link #activate} against a concurrent {@link #publish}/{@link #closeAll}: making the
     * subscription visible in {@code entries} and enqueueing its ack event must happen as one
     * atomic step, or a matching change firing in the gap between them is either dropped (never
     * delivered to this subscriber, because {@code entries} didn't have it yet) or delivered ahead
     * of the ack (forbidden — SEP-2575 requires the ack to be the first message on the stream).
     * Held only across cheap, non-blocking work (a map put, building a small JSON object, and
     * submitting an SSE write to the channel's event loop — never a network wait), so contention is
     * a non-issue. Also serializes {@link #register}.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private boolean closed;

    public SubscriptionRegistry(ServerEngine server) {
        this.server = server;
    }

    /**
     * Makes {@code topic} available to {@code subscriptions/listen} filters. Call while the server
     * starts up, before listeners connect.
     *
     * @throws IllegalArgumentException if a topic with the same {@code filterKey} is registered
     */
    public void register(SubscriptionTopic<?> topic) {
        lock.lock();
        try {
            if (topics.containsKey(topic.filterKey())) {
                throw new IllegalArgumentException("Subscription topic already registered: " + topic.filterKey());
            }
            var updated = new LinkedHashMap<>(topics);
            updated.put(topic.filterKey(), topic);
            topics = Collections.unmodifiableMap(updated);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads every registered topic from the raw {@code notifications} filter. Keys no topic owns are
     * ignored.
     *
     * @throws RequestMappingException with {@code invalid_params} if a topic's value has the wrong JSON type
     */
    public SubscriptionFilter decode(DispatchContext context, JsonObject rawFilter) {
        var requested = new LinkedHashMap<SubscriptionTopic<?>, Object>();
        for (var topic : topics.values()) {
            Object value;
            try {
                value = topic.decoder().decode(context, rawFilter);
            } catch (IllegalArgumentException e) {
                throw new RequestMappingException(ServerErrors.invalidParams(e.getMessage()));
            }
            if (value != null) {
                requested.put(topic, value);
            }
        }
        return new SubscriptionFilter(requested);
    }

    /**
     * Registers a new subscription and sends its ack-first {@code
     * notifications/subscriptions/acknowledged} event, atomically with respect to concurrent {@link
     * #publish}/{@link #closeAll} calls — see {@link #lock}. Returns the registry key for a later
     * {@link #remove}. After {@link #closeAll}, the ack is followed at once by the graceful result.
     */
    public long activate(
            RequestId subscriptionId,
            OutboundSseStream stream,
            SubscriptionFilter filter,
            ProtocolResponseMapper responseMapper,
            CompletableFuture<Object> pendingResponse) {
        lock.lock();
        try {
            var key = nextKey.incrementAndGet();
            var ackParams = responseMapper.subscriptionsAcknowledgedParams(subscriptionId, filter.acknowledged());
            push(stream, responseMapper, "notifications/subscriptions/acknowledged", ackParams);
            if (closed) {
                pendingResponse.complete(responseMapper.subscriptionsListenGracefulResult(subscriptionId));
            } else {
                entries.put(key, new Entry(subscriptionId, stream, filter, responseMapper, pendingResponse));
            }
            return key;
        } finally {
            lock.unlock();
        }
    }

    /** Removes a subscription without completing its response, e.g. on client disconnect. */
    public void remove(long key) {
        entries.remove(key);
    }

    /**
     * Pushes {@code method} to every subscription that opted into {@code topic} with a value {@code
     * wants} accepts. The stream's own subscription id is merged into the params' {@code _meta}.
     *
     * @param params builds the notification params once per protocol mapper in play
     */
    public <F> void publish(
            SubscriptionTopic<F> topic,
            Predicate<? super F> wants,
            String method,
            Function<ProtocolResponseMapper, Object> params) {
        lock.lock();
        try {
            var built = new IdentityHashMap<ProtocolResponseMapper, Object>();
            for (var entry : entries.values()) {
                var requested = entry.filter().get(topic);
                if (requested == null || !wants.test(requested)) continue;
                var mapper = entry.responseMapper();
                var base = built.computeIfAbsent(mapper, params);
                push(
                        entry.stream(),
                        mapper,
                        method,
                        mapper.subscriptionNotificationParams(entry.subscriptionId(), base));
            }
        } finally {
            lock.unlock();
        }
    }

    private void push(OutboundSseStream stream, ProtocolResponseMapper responseMapper, String method, Object params) {
        var notificationJson = JsonRpcCodec.serializeNotificationAsString(method, responseMapper.encode(params));
        var sseEvent = new SseEvent(
                ServerEngine.wireEventId(server.nextEventId(), stream.streamKey()), "message", notificationJson);
        // Fan-out under the registry lock: a slow subscriber is closed, never waited on.
        stream.offerEvent(sseEvent);
    }

    /**
     * Gracefully tears down every open subscription: completes each deferred response with a
     * protocol-specific {@code resultType: "complete"} result, which the dispatcher then writes as
     * the stream's final response before closing it. Called on server shutdown; later activations
     * complete the same way until {@link #reopen}.
     */
    public void closeAll() {
        lock.lock();
        try {
            closed = true;
            for (var key : List.copyOf(entries.keySet())) {
                var entry = entries.remove(key);
                if (entry == null) continue;
                entry.pendingResponse()
                        .complete(entry.responseMapper().subscriptionsListenGracefulResult(entry.subscriptionId()));
            }
        } finally {
            lock.unlock();
        }
    }

    /** Registers subscriptions again after {@link #closeAll}, for a transport restart. */
    public void reopen() {
        lock.lock();
        try {
            closed = false;
        } finally {
            lock.unlock();
        }
    }
}
