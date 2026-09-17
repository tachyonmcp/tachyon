/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.subscriptions;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper.SubscriptionListenRequest;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A real network round-trip can't exercise the race {@link SubscriptionRegistry#activate} guards
 * against: by the time an e2e client observes the ack, {@code activate} has already returned
 * server-side, so the window is only reachable with a directly-injected concurrent caller.
 */
class SubscriptionRegistryTest {

    private final ServerEngine engine = newEngine(b -> {});
    private final SubscriptionRegistry registry = new SubscriptionRegistry(engine);
    private final ProtocolResponseMapper responseMapper = Protocols.list().stream()
            .map(Protocol::responseMapper)
            .filter(m -> m.supports("mcp", "2026-07-28"))
            .findFirst()
            .orElseThrow();

    @AfterEach
    void tearDown() {
        engine.close();
    }

    /**
     * Fires {@code notifyToolsListChanged} from a second thread exactly while the ack event for a
     * brand-new subscription is being written — the tightest possible window between "subscription
     * exists" and "ack visible". If {@link SubscriptionRegistry#activate} ever again split those
     * into two unsynchronized steps, this reproduces the original bug: either the change is dropped
     * (subscription wasn't registered when the notifier ran) or arrives before the ack.
     */
    @Test
    @Timeout(10)
    void concurrentChangeDuringActivationIsNotLostAndFollowsTheAck() throws Exception {
        var notifierStarted = new CountDownLatch(1);
        var notifierDone = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<SseEvent>();

        Consumer<SseEvent> onAckWrite = event -> {
            var notifier = new Thread(
                    () -> {
                        notifierStarted.countDown();
                        registry.notifyToolsListChanged();
                        notifierDone.countDown();
                    },
                    "concurrent-notifier");
            notifier.setDaemon(true);
            notifier.start();
            // Give the notifier a real chance to reach registry.notifyToolsListChanged() and block
            // on the activation lock before this thread releases it — otherwise the test could pass
            // vacuously just because the notifier never got scheduled in time.
            awaitLatch(notifierStarted);
        };
        var stream = new RecordingStream(events, onAckWrite);

        var pending = new CompletableFuture<>();
        registry.activate(
                RequestId.of(1L),
                stream,
                new SubscriptionListenRequest(true, false, false, Set.of(), Set.of()),
                responseMapper,
                pending);

        awaitLatch(notifierDone);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).contains("notifications/subscriptions/acknowledged");
        assertThat(events.get(1).data()).contains("notifications/tools/list_changed");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("latch reached within timeout")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting latch", e);
        }
    }

    /** Records every event written, invoking {@code onAck} synchronously when the ack event lands. */
    private static final class RecordingStream implements OutboundSseStream {
        private final CopyOnWriteArrayList<SseEvent> events;
        private final Consumer<SseEvent> onAckWrite;

        RecordingStream(CopyOnWriteArrayList<SseEvent> events, Consumer<SseEvent> onAckWrite) {
            this.events = events;
            this.onAckWrite = onAckWrite;
        }

        @Override
        public CompletionStage<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean started() {
            return true;
        }

        @Override
        public void writeEvent(@Nullable SseEvent event) {
            if (event == null) return;
            events.add(event);
            if (event.data().contains("acknowledged")) {
                onAckWrite.accept(event);
            }
        }

        @Override
        public void close() {}
    }
}
