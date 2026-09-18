/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.opentelemetry;

import static dev.tachyonmcp.opentelemetry.McpAttributes.EXECUTE_TOOL;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_OPERATION_NAME;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_TOOL_CALL_ARGUMENTS;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_TOOL_CALL_RESULT;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_TOOL_NAME;
import static dev.tachyonmcp.opentelemetry.McpAttributes.MCP_METHOD_NAME;
import static dev.tachyonmcp.opentelemetry.McpAttributes.MCP_PROTOCOL_VERSION;
import static dev.tachyonmcp.opentelemetry.McpAttributes.MCP_SESSION_ID;
import static dev.tachyonmcp.opentelemetry.McpAttributes.TOOL_ERROR;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static io.opentelemetry.semconv.ErrorAttributes.ERROR_TYPE;
import static io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_MESSAGE;
import static io.opentelemetry.semconv.NetworkAttributes.NETWORK_PROTOCOL_NAME;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_PORT;
import static io.opentelemetry.semconv.incubating.JsonrpcIncubatingAttributes.JSONRPC_PROTOCOL_VERSION;
import static io.opentelemetry.semconv.incubating.JsonrpcIncubatingAttributes.JSONRPC_REQUEST_ID;
import static io.opentelemetry.semconv.incubating.RpcIncubatingAttributes.RPC_RESPONSE_STATUS_CODE;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.ObservabilityConfig;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationKind;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestServers;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E2E: a real Tachyon server with {@link McpOpenTelemetryListener} registered via {@code
 * .observability(...)}, driven over HTTP by the testkit client, asserting the spans and metrics an
 * OpenTelemetry backend would receive.
 *
 * <p>Conventions under test: <a
 * href="https://github.com/open-telemetry/semantic-conventions-genai/tree/main/model/mcp">
 * semantic-conventions-genai / model / mcp</a>.
 */
class McpOpenTelemetryListenerTest {

    private InMemorySpanExporter spans;
    private InMemoryMetricReader metrics;
    private OpenTelemetrySdk otel;

    /** Span id {@code Span.current()} reported inside the {@code nesting} tool handler. */
    private final AtomicReference<String> handlerSpanId = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        spans = InMemorySpanExporter.create();
        metrics = InMemoryMetricReader.create();
        otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans))
                        .build())
                .setMeterProvider(
                        SdkMeterProvider.builder().registerMetricReader(metrics).build())
                .build();
    }

    @AfterEach
    void tearDown() {
        otel.close();
    }

    @Test
    @DisplayName("tools/call produces a SERVER span '{method} {tool}' with GenAI attributes and no payload")
    void toolCallSpan() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(sessionId, callForecast());

            assertThat(response).isSuccess().hasTextContent("sunny");

            var span = spanFor("tools/call forecast");
            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(MCP_METHOD_NAME, "tools/call")
                    .containsEntry(GEN_AI_TOOL_NAME, "forecast")
                    .containsEntry(GEN_AI_OPERATION_NAME, EXECUTE_TOOL)
                    .containsEntry(MCP_SESSION_ID, sessionId)
                    .containsEntry(MCP_PROTOCOL_VERSION, Mcp20251125Client.PROTOCOL_VERSION)
                    .containsEntry(JSONRPC_PROTOCOL_VERSION, "2.0")
                    .containsEntry(NETWORK_PROTOCOL_NAME, "http")
                    .containsEntry(SERVER_ADDRESS, server.host())
                    .containsEntry(SERVER_PORT, (long) server.port())
                    .containsEntry(JSONRPC_REQUEST_ID, "10")
                    .doesNotContainKey(ERROR_TYPE)
                    .doesNotContainKey(RPC_RESPONSE_STATUS_CODE)
                    // opt-in per the conventions: arguments carry credentials and personal data
                    .doesNotContainKey(GEN_AI_TOOL_CALL_ARGUMENTS);
        }
    }

    @Test
    @DisplayName("PayloadCapturePolicy.requestArgs(true) records tool arguments as JSON")
    void payloadsWhenOptedIn() throws Exception {
        try (var server = startServer(o -> o.payloadCapture(p -> p.requestArgs(true)));
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            client.post(sessionId, callForecast());

            assertThat(spanFor("tools/call forecast").getAttributes().get(GEN_AI_TOOL_CALL_ARGUMENTS))
                    .contains("\"city\"")
                    .contains("Berlin");
        }
    }

    @Test
    @DisplayName("a tool returning ToolResult.error is a JSON-RPC success but an ERROR span: error.type=tool_error")
    void toolErrorIsClassified() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"failing","arguments":{}}}""");

            assertThat(response).isSuccess().isToolError();

            var span = spanFor("tools/call failing");
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(ERROR_TYPE, TOOL_ERROR)
                    .doesNotContainKey(RPC_RESPONSE_STATUS_CODE)
                    // gen_ai.tool.call.result is opt-in (PayloadCapturePolicy.responseContent) and
                    // off here -- this size pins the full attribute set so it can't leak in silently.
                    .hasSize(11);
        }
    }

    @Test
    @DisplayName("PayloadCapturePolicy.responseContent(true) records a successful tool result as JSON")
    void responseContentWhenOptedInOnSuccess() throws Exception {
        try (var server = startServer(o -> o.payloadCapture(p -> p.responseContent(true)));
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            client.post(sessionId, callForecast());

            assertThat(spanFor("tools/call forecast").getAttributes().get(GEN_AI_TOOL_CALL_RESULT))
                    .contains("sunny");
        }
    }

    @Test
    @DisplayName("PayloadCapturePolicy.responseContent(true) records a failing tool result as JSON too")
    void responseContentWhenOptedInOnToolError() throws Exception {
        try (var server = startServer(o -> o.payloadCapture(p -> p.responseContent(true)));
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"failing","arguments":{}}}""");

            assertThat(response).isSuccess().isToolError();

            assertThat(spanFor("tools/call failing").getAttributes().get(GEN_AI_TOOL_CALL_RESULT))
                    .contains("nope");
        }
    }

    @Test
    @DisplayName("a throwing tool fails the span: JSON-RPC -32603, status ERROR")
    void throwingToolFailsSpan() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"throwing","arguments":{}}}""");

            assertThat(response).isJsonRpcError().hasErrorCode(-32603);

            var span = spanFor("tools/call throwing");
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(ERROR_TYPE, "INTERNAL_ERROR")
                    .containsEntry(RPC_RESPONSE_STATUS_CODE, "-32603");
            // opt-in per PayloadCapturePolicy.exceptionDetail (default false): no exception event
            assertThat(span.getEvents()).isEmpty();
        }
    }

    @Test
    @DisplayName("PayloadCapturePolicy.exceptionDetail(true) records the handler exception on the span")
    void exceptionDetailWhenOptedIn() throws Exception {
        try (var server = startServer(o -> o.payloadCapture(p -> p.exceptionDetail(true)));
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"throwing","arguments":{}}}""");

            assertThat(response).isJsonRpcError().hasErrorCode(-32603);

            var span = spanFor("tools/call throwing");
            assertThat(span.getEvents())
                    .anySatisfy(event -> assertThat(event.getAttributes().get(EXCEPTION_MESSAGE))
                            .contains("boom"));
        }
    }

    @Test
    @DisplayName("PayloadCapturePolicy.exceptionDetail(true) records a resource handler exception on the span")
    void exceptionDetailWhenOptedInForResource() throws Exception {
        try (var server = startServer(o -> o.payloadCapture(p -> p.exceptionDetail(true)));
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":18,"method":"resources/read","params":{"uri":"resource://throwing"}}""");

            assertThat(response).isJsonRpcError().hasErrorCode(-32603);

            var span = spanFor("resources/read");
            assertThat(span.getEvents())
                    .anySatisfy(event -> assertThat(event.getAttributes().get(EXCEPTION_MESSAGE))
                            .contains("boom"));
        }
    }

    @Test
    @DisplayName("PayloadCapturePolicy.exceptionDetail(true) records a prompt handler exception on the span")
    void exceptionDetailWhenOptedInForPrompt() throws Exception {
        try (var server = startServer(o -> o.payloadCapture(p -> p.exceptionDetail(true)));
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":19,"method":"prompts/get","params":{"name":"throwing"}}""");

            assertThat(response).isJsonRpcError().hasErrorCode(-32603);

            var span = spanFor("prompts/get throwing");
            assertThat(span.getEvents())
                    .anySatisfy(event -> assertThat(event.getAttributes().get(EXCEPTION_MESSAGE))
                            .contains("boom"));
        }
    }

    @Test
    @DisplayName("PayloadCapturePolicy.exceptionDetail(true) records a completion handler exception on the span")
    void exceptionDetailWhenOptedInForCompletion() throws Exception {
        try (var server = startServer(o -> o.payloadCapture(p -> p.exceptionDetail(true)));
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":20,"method":"completion/complete","params":{
                      "ref":{"type":"ref/prompt","name":"throwing"},
                      "argument":{"name":"language","value":"java"}
                    }}""");

            assertThat(response).isJsonRpcError().hasErrorCode(-32603);

            var span = spanFor("completion/complete");
            assertThat(span.getEvents())
                    .anySatisfy(event -> assertThat(event.getAttributes().get(EXCEPTION_MESSAGE))
                            .contains("boom"));
        }
    }

    @Test
    @DisplayName("an unknown tool is a caller-fault rejection, but still an ERROR span per MCP semconv")
    void rejectionSetsErrorStatus() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"absent","arguments":{}}}""");

            assertThat(response).isJsonRpcError().hasErrorCode(-32602);

            var span = spanFor("tools/call");
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(RPC_RESPONSE_STATUS_CODE, "-32602")
                    .containsEntry(ERROR_TYPE, "INVALID_PARAMS")
                    .doesNotContainKey(GEN_AI_TOOL_NAME);
        }
    }

    @Test
    @DisplayName(
            "an unrecognized method is rejected with the JSON-RPC wire code on both the span and the duration metric")
    void unknownMethodRejectionRecordsWireCodeOnSpanAndMetric() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":15,"method":"definitely/unknown"}""");

            assertThat(response).isJsonRpcError().hasErrorCode(-32601);

            var span = spanFor("definitely/unknown");
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(RPC_RESPONSE_STATUS_CODE, "-32601")
                    .containsEntry(ERROR_TYPE, "METHOD_NOT_FOUND");

            var histogram = metrics.collectAllMetrics().stream()
                    .filter(metric -> "mcp.server.operation.duration".equals(metric.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(histogram.getHistogramData().getPoints())
                    .anySatisfy(point -> assertThat(point.getAttributes().asMap())
                            .containsEntry(RPC_RESPONSE_STATUS_CODE, "-32601"));
        }
    }

    @Test
    @DisplayName("many unknown tool names collapse into one bounded span name and metric dimension")
    void unresolvedTargetNamesStayBounded() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            for (var i = 0; i < 5; i++) {
                client.post(sessionId, """
                        {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"bogus-%d","arguments":{}}}""".formatted(20 + i, i));
            }

            var matching = spans.getFinishedSpanItems().stream()
                    .filter(s -> "tools/call".equals(s.getName()))
                    .toList();
            assertThat(matching).hasSize(5);
            assertThat(matching)
                    .allSatisfy(s -> assertThat(s.getAttributes().asMap()).doesNotContainKey(GEN_AI_TOOL_NAME));
        }
    }

    @Test
    @DisplayName(
            "two listeners nest: the second's span is a child of the first's, and the handler runs inside the innermost")
    void nestedListenersProduceNestedSpansWithHandlerInsideInnermost() throws Exception {
        // ObservationScope contract: a listener registered later opens its scope inside one
        // registered earlier, so dispatch work runs under the innermost listener's context.
        try (var server = startServer(o -> o.listener(McpOpenTelemetryListener.create(otel)));
                var client = new Mcp20251125Client(server.port())) {
            final var sessionId = client.initialize();
            final var response = client.post(sessionId, callNesting());
            assertThat(response).isSuccess().hasTextContent("nested");

            final var serverSpans = spansFor("tools/call nesting");
            assertThat(serverSpans).as("one span per registered listener").hasSize(2);

            final var roots = serverSpans.stream()
                    .filter(span -> !span.getParentSpanContext().isValid())
                    .toList();
            assertThat(roots)
                    .as("only the first-registered listener's span is parentless")
                    .hasSize(1);
            final var outerSpanId = roots.getFirst().getSpanId();

            final var nested = serverSpans.stream()
                    .filter(span ->
                            outerSpanId.equals(span.getParentSpanContext().getSpanId()))
                    .toList();
            assertThat(nested)
                    .as("the second-registered listener's span is a child of the first's")
                    .hasSize(1);

            assertThat(handlerSpanId.get())
                    .as("the handler runs under the innermost listener's span")
                    .isEqualTo(nested.getFirst().getSpanId());
        }
    }

    @Test
    @DisplayName("notifications are traced, without a jsonrpc.request.id")
    void notificationSpan() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            var span = awaitSpanFor("notifications/initialized");
            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(MCP_METHOD_NAME, "notifications/initialized")
                    .doesNotContainKey(JSONRPC_REQUEST_ID);
        }
    }

    @Test
    @DisplayName("subscriptions/listen is traced while its stream delivers server notifications")
    void subscriptionStreamDeliversNotificationAndProducesSpan() throws Exception {
        try (var server = startServer();
                var client = new Mcp20260728Client(server.port())) {
            try (var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":50,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """)) {
                final var acknowledged = stream.await(
                        frame -> frame.data().contains("notifications/subscriptions/acknowledged"),
                        Duration.ofSeconds(5));

                server.tools()
                        .register(
                                tool -> tool.name("subscription-trigger"),
                                (ctx, request) -> ToolResult.text("triggered"));

                final var changed = stream.await(
                        frame -> frame.data().contains("notifications/tools/list_changed"), Duration.ofSeconds(5));

                assertThatJson(acknowledged.data()).isEqualTo("""
                    {
                      "jsonrpc":"2.0",
                      "method":"notifications/subscriptions/acknowledged",
                      "params":{
                        "notifications":{"toolsListChanged":true},
                        "_meta":{"io.modelcontextprotocol/subscriptionId":50}
                      }
                    }
                    """);
                assertThatJson(changed.data()).isEqualTo("""
                    {
                      "jsonrpc":"2.0",
                      "method":"notifications/tools/list_changed",
                      "params":{"_meta":{"io.modelcontextprotocol/subscriptionId":50}}
                    }
                    """);
            }
            // The span now spans the stream's whole lifetime -- it only finishes once the client
            // closes the stream above, not at the establishment handshake.
            final var span = awaitSpanFor("subscriptions/listen");
            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(MCP_METHOD_NAME, "subscriptions/listen")
                    .containsEntry(JSONRPC_REQUEST_ID, "50")
                    .doesNotContainKey(MCP_SESSION_ID);
        }
    }

    @Test
    @DisplayName("mcp.server.operation.duration for subscriptions/listen measures the ack, not the stream's lifetime")
    void subscriptionDurationMetricMeasuresAckNotStreamLifetime() throws Exception {
        try (var server = startServer();
                var client = new Mcp20260728Client(server.port())) {
            try (var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":53,"method":"subscriptions/listen","params":{}}
                """)) {
                stream.await(
                        frame -> frame.data().contains("notifications/subscriptions/acknowledged"),
                        Duration.ofSeconds(5));
                // The stream stays open well past the ack -- the recorded duration must not include this.
                Thread.sleep(1_000);
            }
            // The server detects the close (and records the metric) asynchronously relative to the
            // client closing its side -- wait for the span first, since it's recorded in the same
            // complete() call as the metric.
            awaitSpanFor("subscriptions/listen");

            var histogram = metrics.collectAllMetrics().stream()
                    .filter(metric -> "mcp.server.operation.duration".equals(metric.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(histogram.getHistogramData().getPoints())
                    .filteredOn(point ->
                            "subscriptions/listen".equals(point.getAttributes().get(MCP_METHOD_NAME)))
                    .as("recorded duration should reflect the ack, not the ~1s the stream stayed open after it")
                    .isNotEmpty()
                    .allSatisfy(point -> assertThat(point.getSum()).isLessThan(0.5));
        }
    }

    @Test
    void subscriptionCompletionRunsOffNettyEventLoop() throws Exception {
        var completionThread = new CompletableFuture<Thread>();
        var observer = new ObservationListener() {
            @Override
            public ObservationScope start(OperationInfo info) {
                return ObservationScope.NOOP;
            }

            @Override
            public void complete(OperationInfo info, OperationOutcome outcome) {
                if (info.method().equals("subscriptions/listen")) {
                    completionThread.complete(Thread.currentThread());
                }
            }
        };
        try (var server = startServer(o -> o.listener(observer));
                var client = new Mcp20260728Client(server.port())) {
            try (var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":51,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """)) {
                stream.await(
                        frame -> frame.data().contains("notifications/subscriptions/acknowledged"),
                        Duration.ofSeconds(5));
            }
            var thread = completionThread.get(5, TimeUnit.SECONDS);
            assertThat(thread.isVirtual()).isTrue();
            assertThat(thread.getName()).doesNotStartWith("netty-");
            assertThat(awaitSpanFor("subscriptions/listen").getStatus().getStatusCode())
                    .isEqualTo(StatusCode.UNSET);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("a subscriptions/listen stream that fails genuinely is an ERROR span: error.type=cause class name")
    void subscriptionStreamFailureFailsSpan(boolean exceptionDetail) {
        var listener = McpOpenTelemetryListener.create(otel);
        var info = OperationInfo.builder(OperationKind.REQUEST, "subscriptions/listen", RequestId.of(99))
                .build();
        var cause = new IOException("connection reset");

        listener.start(info).close();
        listener.complete(
                info, new OperationOutcome.StreamFailed(cause.getClass().getName(), exceptionDetail ? cause : null));

        var span = spanFor("subscriptions/listen");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(ERROR_TYPE)).isEqualTo(IOException.class.getName());
        if (exceptionDetail) {
            assertThat(span.getEvents())
                    .singleElement()
                    .satisfies(event -> assertThat(event.getAttributes().get(EXCEPTION_MESSAGE))
                            .isEqualTo("connection reset"));
        } else {
            assertThat(span.getEvents()).isEmpty();
            assertThat(span.getStatus().getDescription()).doesNotContain("connection reset");
        }
    }

    @Test
    @DisplayName("a streaming tools/call delivers its MCP log notification and produces one tool span")
    void streamingToolLogNotificationProducesSpan() throws Exception {
        try (var server = startServer();
                var client = new Mcp20260728Client(server.port())) {
            try (var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":51,"method":"tools/call",
                 "params":{"name":"logging","arguments":{},
                           "_meta":{"io.modelcontextprotocol/logLevel":"info"}}}
                """)) {
                final var notification =
                        stream.await(frame -> frame.data().contains("notifications/message"), Duration.ofSeconds(5));
                final var result = stream.await(frame -> frame.data().contains("\"result\""), Duration.ofSeconds(5));

                assertThatJson(notification.data()).isEqualTo("""
                    {
                      "jsonrpc":"2.0",
                      "method":"notifications/message",
                      "params":{"level":"info","logger":"otel.probe","data":"streamed log"}
                    }
                    """);
                assertThatJson(result.data()).isEqualTo("""
                    {
                      "jsonrpc":"2.0",
                      "id":51,
                      "result":{
                        "content":[{"type":"text","text":"logged"}],
                        "resultType":"complete"
                      }
                    }
                    """);
            }

            final var span = awaitSpanFor("tools/call logging");
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(MCP_METHOD_NAME, "tools/call")
                    .containsEntry(GEN_AI_TOOL_NAME, "logging")
                    .containsEntry(JSONRPC_REQUEST_ID, "51");
        }
    }

    @Test
    @DisplayName("initialize is traced too; the duration histogram omits high-cardinality ids")
    void durationHistogramCoversEveryOperation() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            client.post(sessionId, callForecast());
            awaitSpanFor("notifications/initialized");

            assertThat(spans.getFinishedSpanItems())
                    .extracting(SpanData::getName)
                    .contains("initialize", "notifications/initialized", "tools/call forecast");

            var histogram = metrics.collectAllMetrics().stream()
                    .filter(metric -> "mcp.server.operation.duration".equals(metric.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(histogram.getUnit()).isEqualTo("s");
            assertThat(histogram.getHistogramData().getPoints())
                    .hasSizeGreaterThanOrEqualTo(3)
                    .allSatisfy(point -> assertThat(point.getAttributes().asMap())
                            .containsKey(MCP_METHOD_NAME)
                            .doesNotContainKey(MCP_SESSION_ID)
                            .doesNotContainKey(JSONRPC_REQUEST_ID));
        }
    }

    @Test
    @DisplayName("resources/read is traced by bare method (no resource target support in this listener)")
    void resourceReadIsTraced() throws Exception {
        try (var server = startServer();
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();
            var response = client.post(
                    sessionId,
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":14,"method":"resources/read","params":{"uri":"resource://greeting"}}""");

            assertThat(response).isSuccess();
            assertThat(spanFor("resources/read").getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        }
    }

    /** {@code id} 10 backs the {@code jsonrpc.request.id} assertion in {@link #toolCallSpan()}. */
    private static String callForecast() {
        // language=json
        return """
                {"jsonrpc":"2.0","id":10,"method":"tools/call",\
                "params":{"name":"forecast","arguments":{"city":"Berlin"}}}""";
    }

    /** Notifications are dispatched off the request thread — the 202 returns before the chain ends. */
    private SpanData awaitSpanFor(String name) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(spans.getFinishedSpanItems())
                        .extracting(SpanData::getName)
                        .contains(name));
        return spanFor(name);
    }

    private static String callNesting() {
        // language=json
        return """
                {"jsonrpc":"2.0","id":11,"method":"tools/call",\
                "params":{"name":"nesting","arguments":{}}}""";
    }

    /** Every finished span with this name -- two listeners each record one per operation. */
    private List<SpanData> spansFor(String name) {
        return spans.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .toList();
    }

    private SpanData spanFor(String name) {
        var matching = spans.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .toList();
        assertThat(matching).as("spans named '%s'", name).hasSize(1);
        return matching.getFirst();
    }

    private TachyonServer startServer() {
        return startServer(o -> {});
    }

    private TachyonServer startServer(Consumer<ObservabilityConfig.Builder> observabilityConfig) {
        return McpTestServers.start(
                builder -> builder.session(session -> session.enabled())
                        .capabilities(capabilities -> capabilities
                                .tools(true)
                                .logging()
                                .resources()
                                .prompts()
                                .completions())
                        .observability(o -> {
                            o.listener(McpOpenTelemetryListener.create(otel));
                            observabilityConfig.accept(o);
                        }),
                server -> {
                    server.tools().register(tool -> tool.name("forecast"), (ctx, request) -> ToolResult.text("sunny"));
                    server.tools().register(tool -> tool.name("failing"), (ctx, request) -> ToolResult.error("nope"));
                    server.tools().register(tool -> tool.name("throwing"), (ctx, request) -> {
                        throw new IOException("🔥 boom");
                    });
                    server.tools().register(tool -> tool.name("nesting"), (ctx, request) -> {
                        handlerSpanId.set(Span.current().getSpanContext().getSpanId());
                        return ToolResult.text("nested");
                    });
                    server.tools().register(tool -> tool.name("logging"), (ctx, request) -> {
                        ctx.notifications().info("otel.probe", "streamed log");
                        return ToolResult.text("logged");
                    });
                    server.resources()
                            .register(
                                    resource -> resource.name("greeting").uri("resource://greeting"),
                                    (ctx, request) -> TextResourceContents.of(request.uri(), "hello", "text/plain"));
                    server.resources()
                            .register(
                                    resource -> resource.name("throwing").uri("resource://throwing"),
                                    (ctx, request) -> {
                                        throw new IOException("🔥 boom");
                                    });
                    server.prompts().register(prompt -> prompt.name("throwing"), (ctx, request) -> {
                        throw new IOException("🔥 boom");
                    });
                    server.completions().registerForPrompt("throwing", (ctx, request) -> {
                        throw new IOException("🔥 boom");
                    });
                });
    }
}
