/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.Backpressure;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.SessionEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerTest {

    @Test
    void createAndRemoveSession() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var conn = new TestConnection();
            var session = server.createSession("sess_1");
            session.connection(conn);

            assertThat(session.state()).isEqualTo(SessionState.INITIALIZING);
            assertThat(server.getSession("sess_1")).isPresent();

            server.removeSession("sess_1");
            assertThat(server.getSession("sess_1")).isEmpty();
            assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        }
    }

    @Test
    void appendResponsePersistsToDurableStore() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var session = server.createSession("sess_1");

            var response = new SessionEvent.ResponseEvent("sess_1", RequestId.of(1), "{\"ok\":true}", 1000L, -1, null);
            var sseEvent = server.appendResponse(session, response);

            assertThat(sseEvent.event()).isEqualTo("message");
            assertThat(sseEvent.data()).isEqualTo("{\"ok\":true}");

            var replayed = server.replay("sess_1", -1);
            assertThat(replayed).hasSize(1);
        }
    }

    @Test
    void replayAfterReconnect() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            server.createSession("sess_1");

            server.appendEvent(new SessionEvent.ResponseEvent("sess_1", RequestId.of(1), "{\"a\":1}", 100L, -1, null));
            server.appendEvent(new SessionEvent.ResponseEvent("sess_1", RequestId.of(2), "{\"b\":2}", 200L, -1, null));
            server.appendEvent(new SessionEvent.ResponseEvent("sess_1", RequestId.of(3), "{\"c\":3}", 300L, -1, null));

            var replayed = server.replay("sess_1", -1);
            assertThat(replayed).hasSize(3);
        }
    }

    @Test
    void backpressureReflectsConnectionState() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var conn = new TestConnection();
            var session = server.createSession("sess_1");
            session.connection(conn);

            conn.writable = true;
            assertThat(server.backpressure(session)).isEqualTo(Backpressure.HOT);

            conn.writable = false;
            assertThat(server.backpressure(session)).isEqualTo(Backpressure.COLD);
        }
    }

    @Test
    void drainEvents() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var conn = new TestConnection();
            var session = server.createSession("sess_1");
            session.connection(conn);

            server.appendEvent(new SessionEvent.ResponseEvent("sess_1", RequestId.of(1), "{\"a\":1}", 100L, -1, null));
            server.appendEvent(
                    new SessionEvent.NotificationEvent("sess_1", "notifications/message", "{}", 200L, -1, null));

            server.drainEvents(session);

            assertThat(conn.sent).hasSize(2);
        }
    }

    @Test
    void replaceExistingSession() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var session1 = server.createSession("sess_1");
            var session2 = server.createSession("sess_1");

            assertThat(session1.state()).isEqualTo(SessionState.CLOSED);
            assertThat(session2.state()).isEqualTo(SessionState.INITIALIZING);
        }
    }

    @Test
    void sendRequestPersistsOutboundRequestEventForReplay() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var session = server.createSession("sess_out");
            session.activate();

            server.sendRequest(session, "sampling/createMessage", Map.of("p", "v"));

            var events = server.replay("sess_out", -1);
            var outbound = events.stream()
                    .filter(e -> e instanceof SessionEvent.OutboundRequestEvent)
                    .map(e -> (SessionEvent.OutboundRequestEvent) e)
                    .toList();
            assertThat(outbound).hasSize(1);
            assertThat(outbound.getFirst().method()).isEqualTo("sampling/createMessage");
            assertThat(outbound.getFirst().sseEventId()).isGreaterThan(0L);
        }
    }

    @Test
    void statelessPendingRequestIsBoundToOutboundChannel() {
        // A stateless server keeps no event log, so the request id is read off the wire frame the
        // stream received rather than replayed from the store.
        try (DefaultTachyonServer server =
                (DefaultTachyonServer) TachyonServer.builder().build()) {
            var session = server.createSession("stateless-request");
            session.activate();
            var stream = new RecordingStream("owning-channel");

            var pending = server.sendRequest(session, "sampling/createMessage", Map.of(), stream);
            var requestId = stream.requestId();

            assertThat(server.replay(session.id(), -1))
                    .as("a stateless server retains no session events")
                    .isEmpty();
            assertThat(server.failPendingRequest(requestId, null, "other-channel", "Rejected"))
                    .isFalse();
            assertThat(pending).isNotDone();
            assertThat(server.completePendingRequest(requestId, null, stream.channelId(), "{}"))
                    .isTrue();
            assertThat(pending).isCompletedWithValue("{}");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingRequestRejectsMissingOwner(boolean sessionsEnabled) {
        try (DefaultTachyonServer server = (DefaultTachyonServer) TachyonServer.builder()
                .session(session -> {
                    if (sessionsEnabled) session.enabled();
                })
                .build()) {
            var requestId = RequestId.of("missing-owner");
            var pending = new CompletableFuture<String>();
            server.registerPendingRequest(requestId, null, null, pending);

            assertThat(server.completePendingRequest(requestId, null, null, "{}"))
                    .isFalse();
            assertThat(pending).isNotDone();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "cancel", "timeout"})
    void terminalCompletionRemovesPendingRequest(String completion) {
        try (final var server = (DefaultTachyonServer) TachyonServer.builder().build()) {
            final var future = new CompletableFuture<String>();
            server.registerPendingRequest(RequestId.of("terminal"), "owner", null, future);
            switch (completion) {
                case "success" -> future.complete("{}");
                case "failure" -> future.completeExceptionally(new IllegalStateException("failed"));
                case "cancel" -> future.cancel(false);
                case "timeout" -> future.completeExceptionally(new TimeoutException());
                default -> throw new AssertionError(completion);
            }
            assertThat(future).isDone();
            assertThat(server).extracting("pendingRequests").asInstanceOf(MAP).isEmpty();
        }
    }

    @Test
    void completionDoesNotRemoveReplacementPendingRequest() {
        try (final var server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            final var id = RequestId.of("reused");
            final var old = new CompletableFuture<String>();
            final var replacement = new CompletableFuture<String>();
            server.registerPendingRequest(id, "owner", null, old);
            server.registerPendingRequest(id, "owner", null, replacement);
            old.cancel(false);
            assertThat(replacement).isNotDone();
            assertThat(server.completePendingRequest(id, "owner", null, "{}")).isTrue();
            assertThat(replacement).isCompletedWithValue("{}");
            assertThat(server).extracting("pendingRequests").asInstanceOf(MAP).isEmpty();
        }
    }

    @Test
    void toSseEventConvertsOutboundRequestEvent() {
        var event = new SessionEvent.OutboundRequestEvent(
                "s", RequestId.of("req-1"), "sampling/createMessage", "{\"p\":\"v\"}", 100L, 7L, null);

        var sseEvent = ServerEngine.toSseEvent(event);

        assertThat(sseEvent).isNotNull();
        assertThat(sseEvent.id()).isEqualTo("7");
        assertThat(sseEvent.event()).isEqualTo("message");
        assertThat(sseEvent.data()).contains("\"method\":\"sampling/createMessage\"");
        assertThat(sseEvent.data()).contains("\"id\":\"req-1\"");
    }

    @Test
    void toSseEventReturnsNullForNonSseEvents() {
        // RequestEvent and CancelEvent have sseEventId=-1 and must return null
        // to prevent NPE in replay path (replayEvents filter fix)
        var requestEvent = new SessionEvent.RequestEvent("s", RequestId.of(1), "ping", "{}", 100L);
        var cancelEvent = new SessionEvent.CancelEvent("s", RequestId.of(1), 100L);

        assertThat(ServerEngine.toSseEvent(requestEvent)).isNull();
        assertThat(ServerEngine.toSseEvent(cancelEvent)).isNull();
    }

    @Test
    void toSseEventConvertsResponseAndNotificationEvents() {
        var response = new SessionEvent.ResponseEvent("s", RequestId.of(1), "{\"ok\":true}", 100L, 5L, null);
        var notification =
                new SessionEvent.NotificationEvent("s", "notifications/tools/list_changed", "{}", 100L, 7L, null);

        var ssResponse = ServerEngine.toSseEvent(response);
        assertThat(ssResponse).isNotNull();
        assertThat(ssResponse.id()).isEqualTo("5");
        assertThat(ssResponse.data()).isEqualTo("{\"ok\":true}");

        var ssNotification = ServerEngine.toSseEvent(notification);
        assertThat(ssNotification).isNotNull();
        assertThat(ssNotification.id()).isEqualTo("7");
        assertThat(ssNotification.data()).contains("notifications/tools/list_changed");
    }

    @Test
    void replayReturnsMixedEventsButToSseEventFiltersNonSse() {
        // Verifies the replay path doesn't NPE on RequestEvent/CancelEvent in the log
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            server.createSession("sess_replay");

            server.appendEvent(new SessionEvent.RequestEvent("sess_replay", RequestId.of(1), "ping", "{}", 100L));
            server.appendEvent(
                    new SessionEvent.ResponseEvent("sess_replay", RequestId.of(1), "{\"pong\":true}", 200L, -1, null));
            server.appendEvent(new SessionEvent.CancelEvent("sess_replay", RequestId.of(2), 300L));

            var allEvents = server.replay("sess_replay", -1);
            assertThat(allEvents).hasSize(3);

            // All events go through toSseEvent; non-SSE must return null (not throw)
            long nonNullCount = allEvents.stream()
                    .map(ServerEngine::toSseEvent)
                    .filter(Objects::nonNull)
                    .count();
            assertThat(nonNullCount).isEqualTo(1); // only the ResponseEvent
        }
    }

    @Test
    void multipleSessions() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            for (int i = 0; i < 10; i++) {
                server.createSession("sess_" + i);
            }

            for (int i = 0; i < 10; i++) {
                assertThat(server.getSession("sess_" + i)).isPresent();
            }
        }
    }

    @Test
    void registerToolSendsListChangedToActiveSession() {
        try (DefaultTachyonServer server = (DefaultTachyonServer) TachyonServer.builder()
                .capabilities(c -> c.toolsListChanged(true))
                .session(s -> s.enabled())
                .build()) {
            var conn = new TestConnection();
            var session = server.createSession("sess_test");
            session.connection(conn);
            session.activate();

            server.tools()
                    .register(
                            builder -> builder.name("dynamic-tool")
                                    .description("Dynamically registered")
                                    // language=json
                                    .inputSchema("{\"type\": \"object\"}"),
                            (context, request) -> ToolResult.empty());

            var listChanged = conn.sent.stream()
                    .filter(e -> e.data().contains("notifications/tools/list_changed"))
                    .toList();
            assertThat(listChanged).isNotEmpty();
        }
    }

    @Test
    void addResourceSendsListChangedToActiveSession() {
        try (DefaultTachyonServer server = (DefaultTachyonServer) TachyonServer.builder()
                .capabilities(c -> c.resourcesListChanged(true))
                .session(s -> s.enabled())
                .build()) {
            var conn = new TestConnection();
            var session = server.createSession("sess_test");
            session.connection(conn);
            session.activate();

            server.resources()
                    .register(
                            ResourceDescriptor.of("dyn", "test://dyn", "Dyn resource", "text/plain"),
                            (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain"));

            var listChanged = conn.sent.stream()
                    .filter(e -> e.data().contains("notifications/resources/list_changed"))
                    .toList();
            assertThat(listChanged).isNotEmpty();
        }
    }

    @Test
    void addPromptSendsListChangedToActiveSession() {
        try (DefaultTachyonServer server = (DefaultTachyonServer) TachyonServer.builder()
                .capabilities(c -> c.promptsListChanged(true))
                .session(s -> s.enabled())
                .build()) {
            var conn = new TestConnection();
            var session = server.createSession("sess_test");
            session.connection(conn);
            session.activate();

            server.prompts().register(PromptDescriptor.of("dyn-prompt", "Dynamic prompt"), List.of());

            var listChanged = conn.sent.stream()
                    .filter(e -> e.data().contains("notifications/prompts/list_changed"))
                    .toList();
            assertThat(listChanged).isNotEmpty();
        }
    }

    @Test
    void listChangedNotSentToNonActiveSession() {
        try (DefaultTachyonServer server = (DefaultTachyonServer) TachyonServer.builder()
                .capabilities(c -> c.toolsListChanged(true))
                .session(s -> s.enabled())
                .build()) {
            var conn = new TestConnection();
            var session = server.createSession("sess_init");
            session.connection(conn);

            server.tools()
                    .register(builder -> builder.name("tool-during-init"), (context, request) -> ToolResult.empty());

            var listChanged = conn.sent.stream()
                    .filter(e -> e.data().contains("list_changed"))
                    .toList();
            assertThat(listChanged).isEmpty();
        }
    }

    @Test
    void notifiesTaskStatusOnlyToOwningSessionWithItsProtocol() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var legacyConnection = new TestConnection();
            var legacy = server.createSession("legacy");
            legacy.protocol(Protocols.list().stream()
                    .filter(protocol -> protocol.versionString().equals("2025-11-25"))
                    .findFirst()
                    .orElseThrow());
            legacy.connection(legacyConnection);
            legacy.activate();

            var modernConnection = new TestConnection();
            var modern = server.createSession("modern");
            modern.protocol(Protocols.list().stream()
                    .filter(protocol -> protocol.versionString().equals("2026-07-28"))
                    .findFirst()
                    .orElseThrow());
            modern.connection(modernConnection);
            modern.activate();

            server.tasksRegistry().create(submitted("legacy-task"), "legacy", null);
            server.tasksRegistry().create(submitted("modern-task"), "modern", null);
            server.tasks().publish(submitted("orphan-task"));

            // SUBMITTED isn't a real wire value in either protocol version's status enum -- both
            // fold it to "working" (verified against the current spec: 5 wire states, no submitted).
            assertThat(legacyConnection.sent)
                    .singleElement()
                    .satisfies(event -> assertThat(event.data())
                            .contains("\"taskId\":\"legacy-task\"")
                            .contains("\"status\":\"working\""));
            assertThat(modernConnection.sent)
                    .singleElement()
                    .satisfies(event -> assertThat(event.data())
                            .contains("\"taskId\":\"modern-task\"")
                            .contains("\"status\":\"working\""));
        }
    }

    @Test
    void reportsTaskProgressOnlyToOwningSession() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var connection = new TestConnection();
            var owner = server.createSession("owner");
            owner.protocol(Protocols.list().stream()
                    .filter(protocol -> protocol.versionString().equals("2025-11-25"))
                    .findFirst()
                    .orElseThrow());
            owner.connection(connection);
            owner.activate();
            var token = ProgressToken.of("tok");
            server.tasksRegistry().create(submitted("owned-task"), "owner", token);
            server.tasksRegistry().create(submitted("orphan-task"), null, token);
            connection.sent.clear();

            // Same token, no owner: must not fall back to any active session.
            server.tasks().reportProgress("orphan-task", 0.25, 1.0, "orphan");
            server.tasks().reportProgress("owned-task", 0.5, 1.0, "owned");

            assertThat(connection.sent)
                    .singleElement()
                    .satisfies(event -> assertThat(event.data())
                            .contains("\"method\":\"notifications/progress\"")
                            .contains("\"progressToken\":\"tok\"")
                            .contains("\"message\":\"owned\"")
                            .doesNotContain("orphan"));
        }
    }

    @Test
    void concurrentTaskPublishersNeverDeliverAStaleStatusAfterANewerOne() throws Exception {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var connection = new TestConnection();
            var owner = server.createSession("owner");
            owner.protocol(Protocols.list().stream()
                    .filter(protocol -> protocol.versionString().equals("2025-11-25"))
                    .findFirst()
                    .orElseThrow());
            owner.connection(connection);
            owner.activate();
            server.tasksRegistry().create(revision(0), "owner", null);

            var publishers = 8;
            var revisions = 400;
            var start = new CountDownLatch(1);
            var threads = new ArrayList<Thread>();
            for (int p = 0; p < publishers; p++) {
                var first = p + 1;
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        for (long r = first; r <= revisions; r += publishers) {
                            server.tasks().publish(revision(r));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (var thread : threads) {
                thread.join();
            }

            var delivered = connection.sent.stream()
                    .map(event -> Long.parseLong(REVISION_MARKER
                            .matcher(event.data())
                            .results()
                            .findFirst()
                            .orElseThrow()
                            .group(1)))
                    .toList();
            assertThat(delivered)
                    .as("status revisions reach the owner in order")
                    .isSorted();
            assertThat(delivered).doesNotHaveDuplicates().last().isEqualTo((long) revisions);
        }
    }

    private static final Pattern REVISION_MARKER = Pattern.compile("\"statusMessage\":\"r(\\d+)\"");

    private static TaskSnapshot revision(long revision) {
        return TaskSnapshot.builder()
                .taskId("raced")
                .status(TaskState.WORKING)
                .statusMessage("r" + revision)
                .createdAt(Instant.EPOCH)
                .lastUpdatedAt(Instant.EPOCH)
                .revision(revision)
                .build();
    }

    private static TaskSnapshot submitted(String taskId) {
        return TaskSnapshot.builder()
                .taskId(taskId)
                .status(TaskState.SUBMITTED)
                .createdAt(Instant.EPOCH)
                .lastUpdatedAt(Instant.EPOCH)
                .revision(1)
                .build();
    }

    @Test
    void sendNotificationShouldNotReachConnectionBeingClosed() throws InterruptedException {
        // E12: broadcastNotification calls session.connection().send() with no lock;
        // session.close() holds the write lock and calls conn.close() first.
        // The race: sender reads session.connection() → still the real conn (NOOP not yet written),
        // then calls conn.send() after conn.close() was called → write-after-close.
        // This test pins the race using latches and verifies the fix prevents it.
        var closedConnSendCount = new AtomicInteger(0);
        var closeInProgress = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);

        var conn = new SseConnection() {
            volatile boolean closeCalled = false;

            @Override
            public boolean isWritable() {
                return true;
            }

            @Override
            public void send(SseEvent event) {
                if (closeCalled) closedConnSendCount.incrementAndGet();
            }

            @Override
            public void close() {
                closeCalled = true;
                closeInProgress.countDown();
                try {
                    releaseClose.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var session = server.createSession("sess_e12");
            session.connection(conn);
            session.activate();

            // Thread A: close() — acquires write lock, CAS state=CLOSED, then blocks in conn.close()
            var closer = Thread.ofVirtual().start(session::close);
            assertThat(closeInProgress.await(2, TimeUnit.SECONDS)).isTrue();

            // Thread B: sendNotification while close() holds the write lock
            // Without fix: reads session.connection() = still real conn → conn.send() called after conn.close()
            // With fix: session.send() blocks on read lock → sees CLOSED after lock → returns false
            var sender =
                    Thread.ofVirtual().start(() -> server.sendNotification(session, "notifications/e12", Map.of()));
            Thread.sleep(20); // give sender time to reach the send call

            releaseClose.countDown(); // let close() complete
            closer.join(2_000);
            sender.join(2_000);

            assertThat(closedConnSendCount.get())
                    .as("notification must not reach a connection that is being closed")
                    .isZero();
        }
    }

    /** Outbound stream that records what was written and reports a stable channel identity. */
    private static final class RecordingStream implements OutboundSseStream {

        private static final Pattern REQUEST_ID = Pattern.compile("\"id\":\"([^\"]+)\"");

        private final String channelId;
        private final List<SseEvent> events = new ArrayList<>();
        private boolean started;

        private RecordingStream(String channelId) {
            this.channelId = channelId;
        }

        @Override
        public String channelId() {
            return channelId;
        }

        @Override
        public CompletionStage<Void> start() {
            started = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean started() {
            return started;
        }

        @Override
        public void writeEvent(@Nullable SseEvent event) {
            if (event != null) events.add(event);
        }

        @Override
        public void close() {}

        RequestId requestId() {
            var matcher = REQUEST_ID.matcher(events.getFirst().data());
            assertThat(matcher.find())
                    .as("outbound request carries a JSON-RPC id")
                    .isTrue();
            return RequestId.of(matcher.group(1));
        }
    }

    private static class TestConnection implements SseConnection {

        volatile boolean writable = true;
        final ArrayList<SseEvent> sent = new ArrayList<>();

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public void send(SseEvent event) {
            sent.add(event);
        }
    }
}
