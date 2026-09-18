/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.AsyncToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.observability.CapturedPayload;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * behaviour of the observation lifecycle wired into {@link McpDispatcher}: the two-call
 * start/complete model, per-operation outcomes, fault isolation, and — most importantly — that a
 * registered listener never forces an async handler onto a blocking join (the defect this design
 * replaces from the interceptor-based PR #288 approach).
 */
class ObservationDispatchTest {

    /** Records every {@code start}/{@code complete} call; each {@code start} returns a fresh, independently trackable scope. */
    private static final class RecordingListener implements ObservationListener {
        record StartCall(OperationInfo info) {}

        record CompleteCall(OperationInfo info, OperationOutcome outcome) {}

        final List<StartCall> starts = new CopyOnWriteArrayList<>();
        final List<CompleteCall> completions = new CopyOnWriteArrayList<>();
        volatile RuntimeException throwOnStart;
        volatile RuntimeException throwOnComplete;

        @Override
        public ObservationScope start(OperationInfo info) {
            starts.add(new StartCall(info));
            if (throwOnStart != null) throw throwOnStart;
            return ObservationScope.NOOP;
        }

        @Override
        public void complete(OperationInfo info, OperationOutcome outcome) {
            completions.add(new CompleteCall(info, outcome));
            if (throwOnComplete != null) throw throwOnComplete;
        }
    }

    /**
     * Stands in for a real context-setting listener such as {@code McpOpenTelemetryListener}: {@code
     * start} and {@code reattach} displace the thread's current context id with this listener's own,
     * and the returned scope restores whatever it displaced. Every close appends one {@code
     * id:seen->restored} row, so a test sees both the order scopes unwound in and what each close left
     * attached to the thread.
     */
    private static final class StackingListener implements ObservationListener {

        private final String id;
        private final ThreadLocal<String> current;
        private final List<String> trace;

        StackingListener(String id, ThreadLocal<String> current, List<String> trace) {
            this.id = id;
            this.current = current;
            this.trace = trace;
        }

        @Override
        public ObservationScope start(OperationInfo info) {
            return attach();
        }

        @Override
        public void complete(OperationInfo info, OperationOutcome outcome) {}

        private ObservationScope attach() {
            final var previous = current.get();
            current.set(id);
            return new ObservationScope() {
                @Override
                public void close() {
                    trace.add(id + ":" + current.get() + "->" + previous);
                    current.set(previous);
                }

                @Override
                public ObservationScope reattach() {
                    return attach();
                }
            };
        }
    }

    private static McpDispatcher.DispatchResult.Response asResponse(McpDispatcher.DispatchResult result) {
        assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
        return (McpDispatcher.DispatchResult.Response) result;
    }

    @Test
    void asyncHandlerNeverBlockedByObservation() throws Exception {
        var reattachClosed = new CompletableFuture<Void>();
        ObservationListener listener = new ObservationListener() {
            @Override
            public ObservationScope start(OperationInfo info) {
                return new ObservationScope() {
                    @Override
                    public void close() {}

                    @Override
                    public ObservationScope reattach() {
                        return new ObservationScope() {
                            @Override
                            public void close() {
                                reattachClosed.complete(null);
                            }

                            @Override
                            public ObservationScope reattach() {
                                return this;
                            }
                        };
                    }
                };
            }

            @Override
            public void complete(OperationInfo info, OperationOutcome outcome) {}
        };

        var gate = new CompletableFuture<ToolResult>();
        AsyncToolFn fn = (ctx, request) -> gate;
        var descriptor =
                ToolDescriptor.builder().name("gated-tool").description("gated").build();

        try (ServerEngine server = newEngine(
                b -> b.observability(o -> o.listener(listener)), s -> s.tools().registerAsync(descriptor, fn))) {
            var session = server.createSession("sess-gate");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());
            var params = Map.of("name", "gated-tool", "arguments", Map.of());
            var future = dispatcher.dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-gate");

            // The scope wrapping decode + handler kickoff must close promptly, proving the
            // dispatcher never joined the still-pending handler stage to get there.
            reattachClosed.get(5, TimeUnit.SECONDS);
            assertThat(future)
                    .as("dispatch stays pending until the handler resolves")
                    .isNotDone();

            gate.complete(ToolResult.text("ok"));
            var result = asResponse(future.get(10, TimeUnit.SECONDS));
            assertThat(result.responseBodyString()).contains("ok");
        }
    }

    @Test
    void ordinaryRequestStartsThenCompletesExactlyOnceAsCompleted() {
        var listener = new RecordingListener();
        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            server.createSession("sess-obs").activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess-obs")
                    .join();

            assertThat(listener.starts).hasSize(1);
            assertThat(listener.starts.getFirst().info().method()).isEqualTo("ping");
            assertThat(listener.completions).hasSize(1);
            assertThat(listener.completions.getFirst().outcome()).isInstanceOf(OperationOutcome.Completed.class);
        }
    }

    @Test
    void rejectionBeforeHandlerReportsRejectedOutcome() {
        var listener = new RecordingListener();
        try (ServerEngine server = (ServerEngine) TachyonServer.builder()
                .session(s -> s.enabled())
                .observability(o -> o.listener(listener))
                .build()) {
            server.createSession("sess-reject").activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "definitely/unknown", null, "sess-reject")
                    .join());
            assertThat(result.responseBodyString()).contains("-32601");

            assertThat(listener.completions).hasSize(1);
            assertThat(listener.completions.getFirst().outcome()).isInstanceOf(OperationOutcome.Rejected.class);
        }
    }

    @Test
    void missingSessionHeaderReportsRejectedOutcomeWithoutServerError() {
        var listener = new RecordingListener();
        try (ServerEngine server = (ServerEngine) TachyonServer.builder()
                .session(s -> s.enabled())
                .observability(o -> o.listener(listener))
                .build()) {
            var dispatcher = new McpDispatcher(server, server.executor());

            // Not "ping" -- ping bypasses the session-header check even without a session.
            var result = dispatcher.dispatchRequestAsync(RequestId.of(1), "tools/list", null, null);
            assertThat(result.join()).isInstanceOf(McpDispatcher.DispatchResult.Status.class);

            assertThat(listener.completions).hasSize(1);
            var outcome =
                    (OperationOutcome.Rejected) listener.completions.getFirst().outcome();
            assertThat(outcome.error()).isNull();
            assertThat(outcome.httpStatus()).isEqualTo(400);
        }
    }

    @Test
    void notificationsInitializedReportsAcceptedAndUnknownReportsIgnored() {
        var listener = new RecordingListener();
        try (ServerEngine server = (ServerEngine) TachyonServer.builder()
                .session(s -> s.enabled())
                .observability(o -> o.listener(listener))
                .build()) {
            server.createSession("sess-notif");
            var dispatcher = new McpDispatcher(server, server.executor());

            dispatcher.dispatchNotification("notifications/initialized", null, "sess-notif");
            dispatcher.dispatchNotification("notifications/unknown-thing", null, "sess-notif");

            assertThat(listener.completions).hasSize(2);
            assertThat(listener.completions.get(0).outcome()).isInstanceOf(OperationOutcome.NotificationAccepted.class);
            assertThat(listener.completions.get(1).outcome()).isInstanceOf(OperationOutcome.NotificationIgnored.class);
        }
    }

    @Test
    void notificationPropagatesTraceparentIntoOperationInfo() {
        var listener = new RecordingListener();
        try (ServerEngine server = (ServerEngine) TachyonServer.builder()
                .session(s -> s.enabled())
                .observability(o -> o.listener(listener))
                .build()) {
            server.createSession("sess-notif-trace");
            var dispatcher = new McpDispatcher(server, server.executor());

            var traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
            var params = Map.of("_meta", Map.of("traceparent", traceparent));
            dispatcher.dispatchNotification("notifications/initialized", params, "sess-notif-trace");

            assertThat(listener.starts).hasSize(1);
            assertThat(listener.starts.getFirst().info().traceparent()).isEqualTo(traceparent);
        }
    }

    @Test
    void statelessNotificationStillReportsIgnoredBeforeAnyEarlyReturn() {
        var listener = new RecordingListener();
        try (ServerEngine server = newEngine(b -> b.observability(o -> o.listener(listener)))) {
            var dispatcher = new McpDispatcher(server, server.executor());

            dispatcher.dispatchNotification("notifications/initialized", null, null);

            assertThat(listener.starts).hasSize(1);
            assertThat(listener.completions).hasSize(1);
            assertThat(listener.completions.getFirst().outcome())
                    .isInstanceOf(OperationOutcome.NotificationIgnored.class);
        }
    }

    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    void throwingListenerDoesNotAffectHandlerOrOtherListeners() {
        var faulty = new RecordingListener();
        faulty.throwOnStart = new RuntimeException("boom on start");
        faulty.throwOnComplete = new RuntimeException("boom on complete");
        var healthy = new RecordingListener();

        try (ServerEngine server =
                newEngine(b -> b.observability(o -> o.listener(faulty).listener(healthy)))) {
            server.createSession("sess-fault").activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess-fault")
                    .join());

            assertThat(result.responseBodyString()).contains("result");
            assertThat(healthy.starts).hasSize(1);
            assertThat(healthy.completions).hasSize(1);
            assertThat(healthy.completions.getFirst().outcome()).isInstanceOf(OperationOutcome.Completed.class);
        }
    }

    @Test
    void nestedListenerScopesCloseInnermostFirstAcrossHops() throws Exception {
        // ObservationScope contract: scopes from multiple listeners nest in registration order and
        // the dispatcher closes them innermost-first, so each close runs under its own context.
        final var current = new ThreadLocal<String>();
        final var trace = new CopyOnWriteArrayList<String>();
        final var handlerSaw = new CopyOnWriteArrayList<String>();
        final var outer = new StackingListener("A", current, trace);
        final var inner = new StackingListener("B", current, trace);

        // Completes the handler's future off the dispatch thread, so the encode phase is a genuine
        // post-hop reattach rather than an inline continuation.
        final var completer = Executors.newSingleThreadExecutor(r -> new Thread(r, "obs-completer"));
        final AsyncToolFn fn = (ctx, request) -> {
            handlerSaw.add(current.get());
            return CompletableFuture.supplyAsync(() -> ToolResult.text("ok"), completer);
        };
        final var descriptor =
                ToolDescriptor.builder().name("nested").description("nested").build();

        try (ServerEngine server = newEngine(
                b -> b.observability(o -> o.listener(outer).listener(inner)),
                s -> s.tools().registerAsync(descriptor, fn))) {
            server.createSession("sess-nested").activate();
            final var dispatcher = new McpDispatcher(server, server.executor());
            final var params = Map.of("name", "nested", "arguments", Map.of());

            final var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-nested")
                    .join());
            assertThat(result.responseBodyString()).contains("ok");

            // Nesting still follows registration order: the later-registered listener is innermost.
            assertThat(handlerSaw).containsExactly("B");

            // More than one phase ran -- the start phase plus at least one post-hop reattach.
            assertThat(trace.size()).isGreaterThanOrEqualTo(4).isEven();

            // Each phase unwinds innermost-first and hands the thread back clean. Under forward
            // closure a phase instead reads ["A:B->null", "B:null->A"], leaving "A" attached.
            for (var i = 0; i < trace.size(); i += 2) {
                assertThat(trace.subList(i, i + 2))
                        .as("phase starting at %d", i)
                        .containsExactly("B:B->A", "A:A->null");
            }
        } finally {
            completer.shutdownNow();
        }
    }

    @Test
    void responseIsIdenticalWithAndWithoutObservationRegistered() {
        try (ServerEngine plain = newEngine(b -> {});
                ServerEngine observed = newEngine(b -> b.observability(o -> o.listener(new RecordingListener())))) {
            plain.createSession("sess-parity").activate();
            observed.createSession("sess-parity").activate();
            var plainDispatcher = new McpDispatcher(plain, plain.executor());
            var observedDispatcher = new McpDispatcher(observed, observed.executor());

            var plainResult = asResponse(plainDispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess-parity")
                    .join());
            var observedResult = asResponse(observedDispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess-parity")
                    .join());

            assertThat(observedResult.responseBodyString()).isEqualTo(plainResult.responseBodyString());
            assertThat(observedResult.httpStatus()).isEqualTo(plainResult.httpStatus());
        }
    }

    @Test
    void taskProducingToolReportsTaskHandoffOutcomeInsteadOfCompleted() {
        var listener = new RecordingListener();
        var connector = TaskConnector.builder()
                .get((ctx, req) -> TaskSnapshot.working(req.taskId(), Instant.now(), 1))
                .cancel((ctx, req) -> {})
                .update((ctx, req) -> {})
                .build();
        var descriptor = ToolDescriptor.builder()
                .name("book")
                .description("books something")
                .taskSupport(TaskSupport.REQUIRED)
                .build();
        var snapshot = TaskSnapshot.working("task-1", Instant.now(), 1);
        AsyncToolFn fn = (ctx, request) -> CompletableFuture.completedFuture(ToolResult.task(snapshot));

        try (ServerEngine server = newEngine(
                b -> b.capabilities(c -> c.tasks(connector)).observability(o -> o.listener(listener)),
                s -> s.tools().registerAsync(descriptor, fn))) {
            server.createSession("sess-task").activate();
            var dispatcher = new McpDispatcher(server, server.executor());
            var params = Map.of("name", "book", "arguments", Map.of(), "task", Map.of());

            var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-task")
                    .join());
            assertThat(result.responseBodyString()).doesNotContain("error");

            assertThat(listener.completions).hasSize(1);
            var outcome = listener.completions.getFirst().outcome();
            assertThat(outcome).isInstanceOf(OperationOutcome.TaskHandoff.class);
            assertThat(((OperationOutcome.TaskHandoff) outcome).taskId()).isEqualTo("task-1");
        }
    }

    @Test
    void requestArgsCapturedOnlyWhenPolicyEnabledAndListenerRegistered() {
        var listener = new RecordingListener();
        var descriptor =
                ToolDescriptor.builder().name("echo").description("echoes").build();
        AsyncToolFn fn = (ctx, request) -> CompletableFuture.completedFuture(ToolResult.text("ok"));

        var noCaptureListener = new RecordingListener();
        try (ServerEngine captured = newEngine(
                        b -> b.observability(o -> o.listener(listener).payloadCapture(p -> p.requestArgs(true))),
                        s -> s.tools().registerAsync(descriptor, fn));
                ServerEngine notCaptured = newEngine(
                        b -> b.observability(o -> o.listener(noCaptureListener)),
                        s -> s.tools().registerAsync(descriptor, fn))) {
            var params = Map.of("name", "echo", "arguments", Map.of("city", "Berlin"));

            captured.createSession("sess-capture").activate();
            var capturingDispatcher = new McpDispatcher(captured, captured.executor());
            capturingDispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-capture")
                    .join();
            var capturedInfo = listener.completions.getFirst().info();
            assertThat(capturedInfo.requestPayload()).isInstanceOf(CapturedPayload.Value.class);
            assertThat(((CapturedPayload.Value) capturedInfo.requestPayload()).json())
                    .contains("Berlin");

            notCaptured.createSession("sess-no-capture").activate();
            var plainDispatcher = new McpDispatcher(notCaptured, notCaptured.executor());
            plainDispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-no-capture")
                    .join();
            assertThat(noCaptureListener.completions.getFirst().info().requestPayload())
                    .isNull();
        }
    }

    @Test
    void responseContentCapturedOnlyWhenPolicyEnabledAndListenerRegistered() {
        var listener = new RecordingListener();
        var descriptor =
                ToolDescriptor.builder().name("echo").description("echoes").build();
        AsyncToolFn fn = (ctx, request) -> CompletableFuture.completedFuture(ToolResult.text("ok"));

        var noCaptureListener = new RecordingListener();
        try (ServerEngine captured = newEngine(
                        b -> b.observability(o -> o.listener(listener).payloadCapture(p -> p.responseContent(true))),
                        s -> s.tools().registerAsync(descriptor, fn));
                ServerEngine notCaptured = newEngine(
                        b -> b.observability(o -> o.listener(noCaptureListener)),
                        s -> s.tools().registerAsync(descriptor, fn))) {
            var params = Map.of("name", "echo", "arguments", Map.of());

            captured.createSession("sess-response-capture").activate();
            var capturingDispatcher = new McpDispatcher(captured, captured.executor());
            capturingDispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-response-capture")
                    .join();
            var capturedInfo = listener.completions.getFirst().info();
            assertThat(capturedInfo.responsePayload()).isInstanceOf(CapturedPayload.Value.class);
            assertThat(((CapturedPayload.Value) capturedInfo.responsePayload()).json())
                    .contains("ok");

            notCaptured.createSession("sess-response-no-capture").activate();
            var plainDispatcher = new McpDispatcher(notCaptured, notCaptured.executor());
            plainDispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-response-no-capture")
                    .join();
            assertThat(noCaptureListener.completions.getFirst().info().responsePayload())
                    .isNull();
        }
    }

    @Test
    void responseContentCapturedOnToolErrorTooWhenPolicyEnabled() {
        var listener = new RecordingListener();
        var descriptor =
                ToolDescriptor.builder().name("failing").description("fails").build();
        AsyncToolFn fn = (ctx, request) -> CompletableFuture.completedFuture(ToolResult.error("nope"));

        try (ServerEngine server = newEngine(
                b -> b.observability(o -> o.listener(listener).payloadCapture(p -> p.responseContent(true))),
                s -> s.tools().registerAsync(descriptor, fn))) {
            server.createSession("sess-response-error-capture").activate();
            var dispatcher = new McpDispatcher(server, server.executor());
            var params = Map.of("name", "failing", "arguments", Map.of());

            dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess-response-error-capture")
                    .join();

            var info = listener.completions.getFirst().info();
            assertThat(info.responsePayload()).isInstanceOf(CapturedPayload.Value.class);
            assertThat(((CapturedPayload.Value) info.responsePayload()).json()).contains("nope");
        }
    }
}
