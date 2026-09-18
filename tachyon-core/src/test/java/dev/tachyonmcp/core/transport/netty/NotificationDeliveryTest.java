/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationDeliveryTest {

    private static final ToolDescriptor TOOL_DESCRIPTOR = ToolDescriptor.builder()
            .name("test_tool")
            .description("Test tool")
            .inputSchema(JsonSchema.objectSchema())
            .build();

    /**
     * Emits 3 progress events and 3 log events per invocation (plus 2 automatic lifecycle logs from ToolsCallHandler).
     */
    private static final ToolFn PROGRESS_AND_LOG_TOOL = (ctx, request) -> {
        var pt = request.progressToken();
        ctx.notifications().progress(pt, 0, 100, "Starting");
        ctx.notifications().progress(pt, 50, 100, "Halfway");
        ctx.notifications().progress(pt, 100, 100, "Complete");
        var logData = Map.of("tool", request.name(), "message", "tool log");
        ctx.notifications().log(LoggingLevel.INFO, "tachyon.tools", logData);
        ctx.notifications().info("tachyon.tools", logData);
        ctx.notifications().info("tachyon.tools", logData);
        return ToolResult.text("ok");
    };

    private static final ToolFn FILTERED_LOG_TOOL = (ctx, request) -> {
        ctx.notifications().log(LoggingLevel.INFO, "filtered.logger", Map.of("message", "skip"));
        ctx.notifications().log(LoggingLevel.ERROR, null);
        return ToolResult.text("ok");
    };

    private ServerEngine server;
    private McpDispatcher dispatcher;
    private CollectingConnection testConn;

    @BeforeEach
    void setUp() {
        server = newEngine(
                b -> b.capabilities(CapabilitiesConfig.Builder::logging).session(s -> s.enabled()),
                s -> s.tools()
                        .register(TOOL_DESCRIPTOR, PROGRESS_AND_LOG_TOOL)
                        .register(builder -> builder.name("filtered_log"), FILTERED_LOG_TOOL));
        dispatcher = new McpDispatcher(server, server.executor());
        testConn = new CollectingConnection();
        Session session = server.createSession("sess_test");
        session.connection(testConn);
        session.activate();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void shouldSendProgressNotification() {
        var params = java.util.Map.of(
                "name", "test_tool",
                "arguments", java.util.Map.of(),
                "_meta", java.util.Map.of("progressToken", 42));
        var result = dispatcher
                .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess_test")
                .join();

        assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
        assertThat(((McpDispatcher.DispatchResult.Response) result).responseBody())
                .isNotNull();

        var progressEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/progress"))
                .toList();
        assertThat(progressEvents).hasSize(3);
        assertThat(progressEvents.get(0).data()).contains("\"progress\":0.0");
        assertThat(progressEvents.get(0).data()).contains("\"progressToken\":42");
        assertThat(progressEvents.get(1).data()).contains("\"progress\":50.0");
        assertThat(progressEvents.get(2).data()).contains("\"progress\":100.0");
    }

    @Test
    void shouldSendProgressNotificationWithStringToken() {
        var params = java.util.Map.of(
                "name", "test_tool",
                "arguments", java.util.Map.of(),
                "_meta", java.util.Map.of("progressToken", "abc123"));
        var result = dispatcher
                .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess_test")
                .join();

        assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
        assertThat(((McpDispatcher.DispatchResult.Response) result).responseBodyString())
                .doesNotContain("\"error\":")
                .doesNotContain("\"isError\":true");

        var progressEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/progress"))
                .toList();
        assertThat(progressEvents).hasSize(3);
        assertThat(progressEvents.get(0).data()).contains("\"progressToken\":\"abc123\"");
    }

    @Test
    void shouldNotSendProgressWithMalformedToken() {
        var params = java.util.Map.of(
                "name", "test_tool",
                "arguments", java.util.Map.of(),
                "_meta", java.util.Map.of("progressToken", true));
        var result = dispatcher
                .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess_test")
                .join();

        assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
        assertThat(((McpDispatcher.DispatchResult.Response) result).responseBodyString())
                .doesNotContain("\"error\":")
                .doesNotContain("\"isError\":true");
        var progressEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/progress"))
                .toList();
        assertThat(progressEvents)
                .as("a non-string/non-numeric progressToken must be treated as absent, not crash the call")
                .isEmpty();
    }

    @Test
    void shouldSendLoggingNotificationWhenLevelIsSet() {
        var levelParams = java.util.Map.of("level", "info");
        dispatcher
                .dispatchRequestAsync(RequestId.of(1), "logging/setLevel", levelParams, "sess_test")
                .join();

        var toolParams = java.util.Map.of(
                "name", "test_tool",
                "arguments", java.util.Map.of(),
                "_meta", java.util.Map.of("progressToken", 42));
        dispatcher
                .dispatchRequestAsync(RequestId.of(2), "tools/call", toolParams, "sess_test")
                .join();

        var logEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/message"))
                .toList();
        assertThat(logEvents).hasSize(3);
        assertThat(logEvents.getFirst().data()).contains("\"level\":\"info\"");
        assertThat(logEvents.getFirst().data()).contains("\"logger\":\"tachyon.tools\"");
    }

    @Test
    void shouldFilterHandlerLogsAtClientThresholdAndAllowMissingLogger() {
        dispatcher
                .dispatchRequestAsync(RequestId.of(1), "logging/setLevel", Map.of("level", "warning"), "sess_test")
                .join();

        dispatcher
                .dispatchRequestAsync(
                        RequestId.of(2),
                        "tools/call",
                        Map.of("name", "filtered_log", "arguments", Map.of()),
                        "sess_test")
                .join();

        var logEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/message"))
                .toList();
        assertThat(logEvents).singleElement().satisfies(event -> {
            assertThat(event.data()).contains("\"level\":\"error\"");
            assertThat(event.data()).contains("\"data\":null");
            assertThat(event.data()).doesNotContain("\"logger\"");
        });
    }

    @Test
    void shouldRejectSetLevelWhenLoggingCapabilityIsDisabled() {
        try (var disabledServer = newEngine(
                b -> b.session(s -> s.enabled()),
                s -> s.tools().register(builder -> builder.name("filtered_log"), FILTERED_LOG_TOOL))) {
            var disabledConnection = new CollectingConnection();
            var disabledSession = disabledServer.createSession("sess_disabled");
            disabledSession.connection(disabledConnection);
            disabledSession.activate();
            var disabledDispatcher = new McpDispatcher(disabledServer, disabledServer.executor());

            var result = disabledDispatcher
                    .dispatchRequestAsync(RequestId.of(1), "logging/setLevel", Map.of("level", "info"), "sess_disabled")
                    .join();

            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
            var responseBody = ((McpDispatcher.DispatchResult.Response) result).responseBodyString();
            assertThat(responseBody).contains("\"code\":-32601").contains("Method not found");

            disabledDispatcher
                    .dispatchRequestAsync(
                            RequestId.of(2),
                            "tools/call",
                            Map.of("name", "filtered_log", "arguments", Map.of()),
                            "sess_disabled")
                    .join();
            assertThat(disabledConnection.sent).noneMatch(event -> event.data().contains("notifications/message"));
        }
    }

    @Test
    void shouldRejectMissingLogLevelAsInvalidParams() {
        var result = dispatcher
                .dispatchRequestAsync(RequestId.of(1), "logging/setLevel", Map.of(), "sess_test")
                .join();

        assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
        assertThat(((McpDispatcher.DispatchResult.Response) result).responseBodyString())
                .contains("\"code\":-32602")
                .contains("Missing level parameter");
    }

    @Test
    void shouldNotSendProgressWithoutMeta() {
        var params = java.util.Map.of("name", "test_tool", "arguments", java.util.Map.of());
        dispatcher
                .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess_test")
                .join();

        var progressEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/progress"))
                .toList();
        assertThat(progressEvents).isEmpty();
    }

    @Test
    void shouldSendLoggingWithoutLevelSet() {
        var params = java.util.Map.of(
                "name", "test_tool",
                "arguments", java.util.Map.of(),
                "_meta", java.util.Map.of("progressToken", 42));
        dispatcher
                .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess_test")
                .join();

        var logEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/message"))
                .toList();
        assertThat(logEvents).hasSize(3);
        assertThat(logEvents).allSatisfy(e -> {
            assertThat(e.data()).contains("\"level\":\"info\"");
            assertThat(e.data()).contains("\"logger\":\"tachyon.tools\"");
        });
    }

    @Test
    void shouldHandleMultipleToolsSequentially() {
        dispatcher
                .dispatchRequestAsync(
                        RequestId.of(1), "logging/setLevel", java.util.Map.of("level", "debug"), "sess_test")
                .join();

        for (int i = 0; i < 3; i++) {
            var params = java.util.Map.of(
                    "name", "test_tool",
                    "arguments", java.util.Map.of(),
                    "_meta", java.util.Map.of("progressToken", i));
            dispatcher
                    .dispatchRequestAsync(RequestId.of(2 + i), "tools/call", params, "sess_test")
                    .join();
        }

        for (int t = 0; t < 3; t++) {
            var token = t;
            var tokenEvents = testConn.sent.stream()
                    .filter(e -> e.data().contains("\"progressToken\":" + token))
                    .toList();
            assertThat(tokenEvents).as("progress events for token " + token).hasSize(3);
            assertThat(tokenEvents.get(0).data()).contains("\"progress\":0.0");
            assertThat(tokenEvents.get(1).data()).contains("\"progress\":50.0");
            assertThat(tokenEvents.get(2).data()).contains("\"progress\":100.0");
        }

        // 3 tools × (3 from askable + 2 from automatic lifecycle logging)
        var logEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/message"))
                .toList();
        assertThat(logEvents).hasSize(15);
        assertThat(logEvents).allSatisfy(e -> {
            assertThat(e.data()).containsAnyOf("\"level\":\"info\"", "\"level\":\"debug\"");
            assertThat(e.data()).contains("\"logger\":\"tachyon.tools\"");
            assertThat(e.data()).contains("\"tool\":\"test_tool\"");
        });
    }

    @Test
    void shouldSendEventOnlyToCorrectSession() {
        var conn2 = new CollectingConnection();
        var session2 = server.createSession("sess_other");
        session2.connection(conn2);
        session2.activate();

        dispatcher
                .dispatchRequestAsync(
                        RequestId.of(1), "logging/setLevel", java.util.Map.of("level", "info"), "sess_test")
                .join();
        dispatcher
                .dispatchRequestAsync(
                        RequestId.of(2), "logging/setLevel", java.util.Map.of("level", "warning"), "sess_other")
                .join();

        var params = java.util.Map.of(
                "name", "test_tool",
                "arguments", java.util.Map.of(),
                "_meta", java.util.Map.of("progressToken", 1));
        dispatcher
                .dispatchRequestAsync(RequestId.of(3), "tools/call", params, "sess_test")
                .join();

        var testLogEvents = testConn.sent.stream()
                .filter(e -> e.data().contains("notifications/message"))
                .toList();
        assertThat(testLogEvents).hasSize(3);
        assertThat(testLogEvents.getFirst().data()).contains("\"level\":\"info\"");

        var otherLogEvents = conn2.sent.stream()
                .filter(e -> e.data().contains("notifications/message"))
                .toList();
        assertThat(otherLogEvents).isEmpty();
    }

    @Test
    void shouldBroadcastServerLogToSessionsAboveTheirOwnThreshold() {
        // server.notifications() fans out, gated by each session's configured threshold
        var warnConn = new CollectingConnection();
        var warnSession = server.createSession("sess_warn");
        warnSession.connection(warnConn);
        warnSession.activate();
        server.setLoggingLevel("sess_warn", LoggingLevel.WARNING);
        server.setLoggingLevel("sess_test", LoggingLevel.DEBUG);

        server.notifications().log(LoggingLevel.INFO, "svc", Map.of("m", "hi"));
        assertThat(logMessages(testConn)).hasSize(1);
        assertThat(logMessages(warnConn)).isEmpty();

        server.notifications().error("svc", Map.of("m", "bad"));
        assertThat(logMessages(testConn)).hasSize(2);
        assertThat(logMessages(warnConn))
                .singleElement()
                .satisfies(e ->
                        assertThat(e.data()).contains("\"level\":\"error\"").contains("\"logger\":\"svc\""));
    }

    @Test
    void shouldNotBroadcastServerLogToInactiveSession() {
        var idleConn = new CollectingConnection();
        var idleSession = server.createSession("sess_idle"); // created but never activated
        idleSession.connection(idleConn);
        server.setLoggingLevel("sess_idle", LoggingLevel.DEBUG);

        server.notifications().error("svc", "boom");

        assertThat(logMessages(idleConn)).isEmpty();
        assertThat(logMessages(testConn)).hasSize(1); // active session at default INFO, ERROR passes
    }

    @Test
    void shouldOmitLoggerAndRetainNullDataInBroadcast() {
        server.setLoggingLevel("sess_test", LoggingLevel.DEBUG);

        server.notifications().log(LoggingLevel.NOTICE, null);

        assertThat(logMessages(testConn))
                .singleElement()
                .satisfies(e -> assertThat(e.data())
                        .contains("\"level\":\"notice\"")
                        .contains("\"data\":null")
                        .doesNotContain("\"logger\""));
    }

    @Test
    void shouldNotBroadcastServerLogWhenLoggingCapabilityDisabled() {
        try (var noLog = newEngine(b -> b.session(s -> s.enabled()))) {
            var conn = new CollectingConnection();
            var session = noLog.createSession("s1");
            session.connection(conn);
            session.activate();
            noLog.setLoggingLevel("s1", LoggingLevel.DEBUG);

            noLog.notifications().error("svc", "x");

            assertThat(conn.sent.stream().filter(e -> e.data().contains("notifications/message")))
                    .isEmpty();
        }
    }

    private static java.util.List<SseEvent> logMessages(CollectingConnection conn) {
        return conn.sent.stream()
                .filter(e -> e.data().contains("notifications/message"))
                .toList();
    }

    private static class CollectingConnection implements SseConnection {

        final ArrayList<SseEvent> sent = new ArrayList<>();
        final boolean writable = true;

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
