/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.subscriptions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper.SubscriptionListenRequest;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Tracks active {@code subscriptions/listen} streams (2026-07-28), independent of any session,
 * and pushes {@code notifications/tools|prompts|resources/list_changed} and {@code
 * notifications/resources/updated} to each stream whose filter opted in.
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
            SubscriptionListenRequest filter,
            ProtocolResponseMapper responseMapper,
            CompletableFuture<Object> pendingResponse) {}

    private final ServerEngine server;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong nextKey = new AtomicLong();

    /**
     * Guards {@link #activate} against a concurrent {@code notifyXxx}/{@link #closeAll}: making the
     * subscription visible in {@code entries} and enqueueing its ack event must happen as one
     * atomic step, or a matching change firing in the gap between them is either dropped (never
     * delivered to this subscriber, because {@code entries} didn't have it yet) or delivered ahead
     * of the ack (forbidden — SEP-2575 requires the ack to be the first message on the stream).
     * Held only across cheap, non-blocking work (a map put, building a small JSON object, and
     * submitting an SSE write to the channel's event loop — never a network wait), so contention is
     * a non-issue.
     */
    private final ReentrantLock lock = new ReentrantLock();

    public SubscriptionRegistry(ServerEngine server) {
        this.server = server;
    }

    /**
     * Registers a new subscription and sends its ack-first {@code
     * notifications/subscriptions/acknowledged} event, atomically with respect to concurrent {@code
     * notifyXxx}/{@link #closeAll} calls — see {@link #lock}. Returns the registry key for a later
     * {@link #remove}.
     */
    public long activate(
            RequestId subscriptionId,
            OutboundSseStream stream,
            SubscriptionListenRequest filter,
            ProtocolResponseMapper responseMapper,
            CompletableFuture<Object> pendingResponse) {
        lock.lock();
        try {
            var key = nextKey.incrementAndGet();
            entries.put(key, new Entry(subscriptionId, stream, filter, responseMapper, pendingResponse));
            var ackParams = responseMapper.subscriptionsAcknowledgedParams(subscriptionId, filter);
            push(stream, responseMapper, "notifications/subscriptions/acknowledged", ackParams);
            return key;
        } finally {
            lock.unlock();
        }
    }

    /** Removes a subscription without completing its response, e.g. on client disconnect. */
    public void remove(long key) {
        entries.remove(key);
    }

    public void notifyToolsListChanged() {
        pushListChanged(SubscriptionListenRequest::toolsListChanged, "notifications/tools/list_changed");
    }

    public void notifyPromptsListChanged() {
        pushListChanged(SubscriptionListenRequest::promptsListChanged, "notifications/prompts/list_changed");
    }

    public void notifyResourcesListChanged() {
        pushListChanged(SubscriptionListenRequest::resourcesListChanged, "notifications/resources/list_changed");
    }

    /**
     * Pushes {@code notifications/tasks} (tasks extension, SEP-2663) to every subscription that
     * opted into {@code snapshot.taskId()} via {@code subscriptions/listen}.
     */
    public void notifyTaskStatus(TaskSnapshot snapshot) {
        lock.lock();
        try {
            for (var entry : entries.values()) {
                if (!entry.filter().taskIds().contains(snapshot.taskId())) continue;
                push(
                        entry.stream(),
                        entry.responseMapper(),
                        "notifications/tasks",
                        entry.responseMapper().taskStatusNotificationParams(snapshot));
            }
        } finally {
            lock.unlock();
        }
    }

    /** Pushes {@code notifications/resources/updated} to every subscription that opted into {@code uri}. */
    public void notifyResourceUpdated(String uri) {
        lock.lock();
        try {
            for (var entry : entries.values()) {
                if (!entry.filter().resourceSubscriptions().contains(uri)) continue;
                push(
                        entry.stream(),
                        entry.responseMapper(),
                        "notifications/resources/updated",
                        entry.responseMapper().subscriptionResourceUpdatedParams(entry.subscriptionId(), uri));
            }
        } finally {
            lock.unlock();
        }
    }

    private void pushListChanged(Predicate<SubscriptionListenRequest> wants, String method) {
        lock.lock();
        try {
            for (var entry : entries.values()) {
                if (!wants.test(entry.filter())) continue;
                push(
                        entry.stream(),
                        entry.responseMapper(),
                        method,
                        entry.responseMapper().subscriptionListChangedParams(entry.subscriptionId()));
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
     * the stream's final response before closing it. Called on server shutdown.
     */
    public void closeAll() {
        lock.lock();
        try {
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
}
