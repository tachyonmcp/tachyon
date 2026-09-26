/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Push traffic of a task reaches only the session that owns it, in revision order. */
class TaskPushRoutingTest {

    /** No legacy {@code tasks/list}: the server then sends no {@code list_changed} next to status pushes. */
    private static final TaskConnector NO_LEGACY_LIST = TaskConnector.builder()
            .get((ctx, request) -> null)
            .cancel((ctx, request) -> {})
            .update((ctx, request) -> {})
            .build();

    private static ServerEngine server() {
        return (ServerEngine) TachyonServer.builder()
                .session(s -> s.enabled())
                .withExtension(TasksExtension.class, t -> t.connector(NO_LEGACY_LIST))
                .build();
    }

    private static Tasks tasks(ServerEngine server) {
        return server.extension(TasksExtension.class).orElseThrow().tasks();
    }

    @Test
    void notifiesTaskStatusOnlyToOwningSessionWithItsProtocol() {
        try (var server = server()) {
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

            server.taskRuntime().publish(submitted("legacy-task"), "legacy", null);
            server.taskRuntime().publish(submitted("modern-task"), "modern", null);
            tasks(server).publish(submitted("orphan-task"));

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
    void reportsTaskProgressOnlyToTheRoutedSession() {
        try (var server = server()) {
            var connection = new TestConnection();
            var owner = server.createSession("owner");
            owner.protocol(Protocols.list().stream()
                    .filter(protocol -> protocol.versionString().equals("2025-11-25"))
                    .findFirst()
                    .orElseThrow());
            owner.connection(connection);
            owner.activate();
            var token = ProgressToken.of("tok");
            server.taskRuntime().publish(submitted("owned-task"), "owner", token);
            server.taskRuntime().publish(submitted("orphan-task"), null, token);
            connection.sent.clear();

            // Same token, no session: must not fall back to any active session.
            tasks(server).reportProgress("orphan-task", 0.25, 1.0, "orphan");
            tasks(server).reportProgress("owned-task", 0.5, 1.0, "owned");

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
        try (var server = server()) {
            var connection = new TestConnection();
            var owner = server.createSession("owner");
            owner.protocol(Protocols.list().stream()
                    .filter(protocol -> protocol.versionString().equals("2025-11-25"))
                    .findFirst()
                    .orElseThrow());
            owner.connection(connection);
            owner.activate();
            server.taskRuntime().publish(revision(0), "owner", null);

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
                            tasks(server).publish(revision(r));
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
