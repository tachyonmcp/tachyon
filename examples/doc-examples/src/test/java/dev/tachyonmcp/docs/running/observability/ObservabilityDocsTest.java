package dev.tachyonmcp.docs.running.observability;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener;
import dev.tachyonmcp.testkit.McpTestServers;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ObservabilityDocsTest {

    private static final String GREET = """
            {"name":"greet","arguments":{"name":"Ada"}}
            """;

    private static final AttributeKey<String> ARGUMENTS = AttributeKey.stringKey("gen_ai.tool.call.arguments");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("gen_ai.tool.call.result");

    @Test
    @Timeout(90)
    void loggingExportersPrintTheSpanAndTheMetricWithoutPayloads() throws Exception {
        try (var forked = ForkedMain.start("dev.tachyonmcp.docs.running.observability.MyMcpServer")) {
            assertThat(text(result(DOCUMENTED_PORT, "tools/call", GREET))).isEqualTo("Hello, Ada!");

            await().atMost(Duration.ofSeconds(10)).until(() -> forked.output().contains("'tools/call greet'"));
            var output = forked.output();
            var span = output.substring(output.indexOf("'tools/call greet'"));
            assertThat(span)
                    .contains("mcp.method.name=tools/call")
                    .contains("gen_ai.tool.name=greet")
                    .contains("gen_ai.operation.name=execute_tool")
                    .contains("mcp.protocol.version=2026-07-28")
                    .contains("jsonrpc.protocol.version=2.0")
                    .contains("network.protocol.name=http")
                    .contains("server.address=127.0.0.1")
                    .contains("server.port=8080");
            assertThat(output).doesNotContain("gen_ai.tool.call.arguments").doesNotContain("Hello, Ada!");

            await().atMost(Duration.ofSeconds(20))
                    .until(() -> forked.output().contains("name=mcp.server.operation.duration"));
        }
    }

    @Test
    void payloadCaptureAddsArgumentsAndResultsToTheSpanOnlyWhenEnabled() throws Exception {
        var captured = new CapturingExporter();
        var openTelemetry = CapturingExporter.sdk(captured);
        try (var server = PayloadCapture.start(openTelemetry)) {
            assertThat(text(result(server, "tools/call", GREET))).isEqualTo("Hello, Ada!");

            await().until(() -> captured.spans.stream().anyMatch(s -> s.getName().equals("tools/call greet")));
            var attributes = captured.span("tools/call greet").getAttributes();
            assertThat(attributes.get(ARGUMENTS)).contains("Ada");
            assertThat(attributes.get(RESULT)).contains("Hello, Ada!");
        }

        var plain = new CapturingExporter();
        var plainTelemetry = CapturingExporter.sdk(plain);
        try (var server = McpTestServers.start(
                b -> b.annotations(a -> a.register(new MyMcpServer.GreetingService()))
                        .observability(o -> o.listener(McpOpenTelemetryListener.create(plainTelemetry))),
                s -> {})) {
            result(server, "tools/call", GREET);

            await().until(() -> plain.spans.stream().anyMatch(s -> s.getName().equals("tools/call greet")));
            var attributes = plain.span("tools/call greet").getAttributes();
            assertThat(attributes.get(ARGUMENTS)).as("capture is off by default").isNull();
            assertThat(attributes.get(RESULT)).isNull();
        }
    }

    @Test
    void twoListenersProduceTwoNestedSpansPerOperation() throws Exception {
        var captured = new CapturingExporter();
        var openTelemetry = CapturingExporter.sdk(captured);
        try (var server = McpTestServers.start(
                b -> b.annotations(a -> a.register(new MyMcpServer.GreetingService()))
                        .observability(o -> o.listener(McpOpenTelemetryListener.create(openTelemetry)))
                        .observability(o -> o.listener(McpOpenTelemetryListener.create(openTelemetry))),
                s -> {})) {
            result(server, "tools/call", GREET);

            await().until(() -> captured.spans.stream().filter(s -> s.getName().equals("tools/call greet")).count() == 2);
            var spans = captured.spans.stream().filter(s -> s.getName().equals("tools/call greet")).toList();
            var outer = spans.stream().filter(s -> !s.getParentSpanContext().isValid() || s.getParentSpanId().equals("0000000000000000")).findFirst();
            assertThat(outer).isPresent();
            assertThat(spans).anyMatch(s -> s.getParentSpanId().equals(outer.get().getSpanId()));
        }
    }

    @Test
    @Timeout(60)
    void customListenerSeesStartAndCompleteForEveryOperation() throws Exception {
        try (var forked = ForkedMain.start("dev.tachyonmcp.docs.running.observability.CustomListenerServer")) {
            result(DOCUMENTED_PORT, "server/discover");

            await().atMost(Duration.ofSeconds(10)).until(() -> forked.output().contains("OBS complete server/discover"));
            assertThat(forked.output())
                    .contains("OBS start server/discover")
                    .contains("OBS complete server/discover Completed");
        }
    }

    @Test
    void aListenerThatThrowsDoesNotAffectTheResponse() throws Exception {
        ObservationListener throwing = new ObservationListener() {
            @Override
            public ObservationScope start(OperationInfo info) {
                throw new IllegalStateException("🔥listener bug: start");
            }

            @Override
            public void complete(OperationInfo info, OperationOutcome outcome) {
                throw new IllegalStateException("🔥listener bug: complete");
            }
        };
        try (TachyonServer server = McpTestServers.start(
                b -> b.annotations(a -> a.register(new MyMcpServer.GreetingService()))
                        .observability(o -> o.listener(throwing)),
                s -> {})) {
            assertThat(text(result(server, "tools/call", GREET))).isEqualTo("Hello, Ada!");
        }
    }
}
