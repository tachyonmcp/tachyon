/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.handlers;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code subscriptions/listen} is the one handler whose returned {@code CompletionStage} spans the
 * whole SSE stream lifetime. Observation must stay open until that stream actually ends, and must
 * classify an ordinary disconnect ({@link OperationOutcome.Cancelled}) separately from a genuine
 * transport failure ({@link OperationOutcome.StreamFailed}).
 */
class SubscriptionsListenObservationTest {

    private static final class FakeStream implements OutboundSseStream {
        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> onCloseRegistered = new CompletableFuture<>();
        private final CompletableFuture<Void> startCompletion = new CompletableFuture<>();
        private volatile @Nullable Consumer<@Nullable Throwable> onClose;

        @Override
        public CompletionStage<Void> start() {
            return startCompletion;
        }

        /** Simulates the ack write actually flushing — until called, {@code start()}'s stage stays pending. */
        void completeStart() {
            startCompletion.complete(null);
        }

        @Override
        public boolean started() {
            return true;
        }

        @Override
        public void writeEvent(@Nullable SseEvent event) {
            if (event != null) events.add(event);
        }

        @Override
        public void close() {}

        @Override
        public void onClose(Consumer<@Nullable Throwable> callback) {
            this.onClose = callback;
            onCloseRegistered.complete(null);
        }

        /** The handler runs asynchronously on the server's executor — wait for it to reach {@link #onClose}. */
        void awaitReady() throws Exception {
            onCloseRegistered.get(5, TimeUnit.SECONDS);
        }

        void disconnect(@Nullable Throwable cause) {
            var callback = onClose;
            if (callback != null) callback.accept(cause);
        }
    }

    private record Fixture(FakeStream stream, CompletableFuture<McpDispatcher.DispatchResult> future) {}

    private static Fixture dispatch(ServerEngine server) {
        var session = server.createSession("sess_sub_listen_obs");
        session.activate();
        var dispatcher = new McpDispatcher(server, server.executor());
        var protocol = Protocols.list().stream()
                .filter(p -> p.versionString().equals("2026-07-28"))
                .findFirst()
                .orElseThrow();
        var ctx = DefaultDispatchContext.create(protocol, server);
        var stream = new FakeStream();
        var future = dispatcher.dispatchRequestAsync(
                RequestId.of(1), "subscriptions/listen", Map.of(), session.id(), stream, ctx);
        return new Fixture(stream, future);
    }

    @Test
    void establishmentDoesNotCompleteObservationWhileStreamStaysOpen() throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completions = new CopyOnWriteArrayList<OperationOutcome>();
        ObservationListener listener = recordingListener(starts, completions);

        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();

            assertThat(starts).hasSize(1);
            assertThat(completions)
                    .as("the Observation stays open for the stream's whole lifetime")
                    .isEmpty();
            assertThat(fixture.future()).isNotDone();
        }
    }

    @Test
    void establishmentNanosIsSetOnlyOnceTheAckWriteFlushes() throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completions = new CopyOnWriteArrayList<OperationOutcome>();
        ObservationListener listener = recordingListener(starts, completions);

        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();

            assertThat(starts.getFirst().establishmentNanos())
                    .as("start() only schedules the ack write; establishmentNanos must not be set before it flushes")
                    .isNull();

            fixture.stream().completeStart();

            assertThat(starts.getFirst().establishmentNanos())
                    .as("set once start()'s stage reports the ack write flushed")
                    .isNotNull();
        }
    }

    @Test
    void ordinaryDisconnectCompletesObservationAsCancelled() throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completions = new CopyOnWriteArrayList<OperationOutcome>();
        ObservationListener listener = recordingListener(starts, completions);

        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();

            fixture.stream().disconnect(null);
            fixture.future().get(5, TimeUnit.SECONDS);

            assertThat(completions).hasSize(1);
            assertThat(completions.getFirst()).isInstanceOf(OperationOutcome.Cancelled.class);
        }
    }

    @Test
    void genuineStreamFailureCompletesObservationAsStreamFailed() throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completions = new CopyOnWriteArrayList<OperationOutcome>();
        ObservationListener listener = recordingListener(starts, completions);
        var cause = new IOException("connection reset");

        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();

            fixture.stream().disconnect(cause);
            fixture.future().get(5, TimeUnit.SECONDS);

            assertThat(completions).hasSize(1);
            assertThat(completions.getFirst()).isInstanceOf(OperationOutcome.StreamFailed.class);
            assertThat(((OperationOutcome.StreamFailed) completions.getFirst()).cause())
                    .isEqualTo(cause);
        }
    }

    private static ObservationListener recordingListener(
            List<OperationInfo> starts, List<OperationOutcome> completions) {
        return new ObservationListener() {
            @Override
            public ObservationScope start(OperationInfo info) {
                starts.add(info);
                return ObservationScope.NOOP;
            }

            @Override
            public void complete(OperationInfo info, OperationOutcome outcome) {
                completions.add(outcome);
            }
        };
    }
}
