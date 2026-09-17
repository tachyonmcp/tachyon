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
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

        /** Simulates the ack write failing, with no {@link #onClose} callback ever following it. */
        void failStart(Throwable cause) {
            startCompletion.completeExceptionally(cause);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void genuineStreamFailureCompletesObservationAsStreamFailed(boolean exceptionDetail) throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completions = new CopyOnWriteArrayList<OperationOutcome>();
        ObservationListener listener = recordingListener(starts, completions);
        var cause = new IOException("connection reset");

        try (ServerEngine server = newEngine(b ->
                b.observability(o -> o.listener(listener).payloadCapture(p -> p.exceptionDetail(exceptionDetail))))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();

            fixture.stream().disconnect(cause);
            fixture.future().get(5, TimeUnit.SECONDS);

            assertThat(completions).hasSize(1);
            assertThat(completions.getFirst()).isInstanceOf(OperationOutcome.StreamFailed.class);
            assertThat(((OperationOutcome.StreamFailed) completions.getFirst()).causeType())
                    .isEqualTo(IOException.class.getName());
            assertThat(((OperationOutcome.StreamFailed) completions.getFirst()).cause())
                    .isEqualTo(exceptionDetail ? cause : null);
        }
    }

    @Test
    void ackWriteFailureWithoutCloseSignalCompletesObservationAsStreamFailed() throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completions = new CopyOnWriteArrayList<OperationOutcome>();
        ObservationListener listener = recordingListener(starts, completions);
        var cause = new IOException("broken pipe");

        try (ServerEngine server = newEngine(
                b -> b.observability(o -> o.listener(listener).payloadCapture(p -> p.exceptionDetail(true))))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();

            fixture.stream().failStart(cause);
            fixture.future().get(5, TimeUnit.SECONDS);

            assertThat(starts.getFirst().establishmentNanos()).isNull();
            assertThat(completions).hasSize(1);
            assertThat(completions.getFirst()).isInstanceOf(OperationOutcome.StreamFailed.class);
            assertThat(((OperationOutcome.StreamFailed) completions.getFirst()).causeType())
                    .isEqualTo(IOException.class.getName());
            assertThat(((OperationOutcome.StreamFailed) completions.getFirst()).cause())
                    .isEqualTo(cause);
        }
    }

    @Test
    void ackOnAlreadyClosedChannelCompletesObservationAsCancelled() throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completions = new CopyOnWriteArrayList<OperationOutcome>();
        ObservationListener listener = recordingListener(starts, completions);

        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();

            fixture.stream().failStart(new ClosedChannelException());
            fixture.future().get(5, TimeUnit.SECONDS);

            assertThat(completions).hasSize(1);
            assertThat(completions.getFirst()).isInstanceOf(OperationOutcome.Cancelled.class);
        }
    }

    @Test
    void shutdownCompletionRetainsAckTimestampFromAnotherThread() throws Exception {
        var starts = new CopyOnWriteArrayList<OperationInfo>();
        var completedTimestamp = new CompletableFuture<Long>();
        var outcome = new CompletableFuture<OperationOutcome>();
        var listener = new ObservationListener() {
            @Override
            public ObservationScope start(OperationInfo info) {
                starts.add(info);
                return ObservationScope.NOOP;
            }

            @Override
            public void complete(OperationInfo info, OperationOutcome result) {
                completedTimestamp.complete(info.establishmentNanos());
                outcome.complete(result);
            }
        };
        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();
            var ack = server.executor().submit(() -> {
                fixture.stream().completeStart();
                return starts.getFirst().establishmentNanos();
            });
            var timestamp = ack.get(5, TimeUnit.SECONDS);
            assertThat(timestamp).isNotNull();

            server.close();

            assertThat(fixture.future()).isDone();
            assertThat(completedTimestamp).isCompletedWithValue(timestamp);
            assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(OperationOutcome.Completed.class);
        }
    }

    @Test
    void disconnectAfterExecutorShutdownStillCompletesObservationOffCallerThread() throws Exception {
        var completion = new CompletableFuture<Thread>();
        ObservationListener listener = new ObservationListener() {
            @Override
            public ObservationScope start(OperationInfo info) {
                return ObservationScope.NOOP;
            }

            @Override
            public void complete(OperationInfo info, OperationOutcome outcome) {
                completion.complete(Thread.currentThread());
            }
        };
        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var fixture = dispatch(server);
            fixture.stream().awaitReady();
            server.executor().shutdown();
            assertThat(server.executor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            fixture.stream().disconnect(null);
            fixture.future().get(5, TimeUnit.SECONDS);
            assertThat(completion.get(5, TimeUnit.SECONDS)).isNotSameAs(Thread.currentThread());
            assertThat(completion.get().isVirtual()).isTrue();
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
