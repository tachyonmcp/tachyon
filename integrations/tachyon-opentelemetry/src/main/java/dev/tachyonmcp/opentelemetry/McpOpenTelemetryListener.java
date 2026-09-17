/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.opentelemetry;

import static dev.tachyonmcp.opentelemetry.McpAttributes.EXECUTE_TOOL;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_OPERATION_NAME;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_PROMPT_NAME;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_TOOL_CALL_ARGUMENTS;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_TOOL_CALL_RESULT;
import static dev.tachyonmcp.opentelemetry.McpAttributes.GEN_AI_TOOL_NAME;
import static dev.tachyonmcp.opentelemetry.McpAttributes.MCP_METHOD_NAME;
import static dev.tachyonmcp.opentelemetry.McpAttributes.MCP_PROTOCOL_VERSION;
import static dev.tachyonmcp.opentelemetry.McpAttributes.MCP_SESSION_ID;
import static dev.tachyonmcp.opentelemetry.McpAttributes.PROMPTS_GET;
import static dev.tachyonmcp.opentelemetry.McpAttributes.TOOLS_CALL;
import static dev.tachyonmcp.opentelemetry.McpAttributes.TOOL_ERROR;
import static io.opentelemetry.semconv.ErrorAttributes.ERROR_TYPE;
import static io.opentelemetry.semconv.NetworkAttributes.NETWORK_PROTOCOL_NAME;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_PORT;
import static io.opentelemetry.semconv.incubating.JsonrpcIncubatingAttributes.JSONRPC_PROTOCOL_VERSION;
import static io.opentelemetry.semconv.incubating.JsonrpcIncubatingAttributes.JSONRPC_REQUEST_ID;
import static io.opentelemetry.semconv.incubating.RpcIncubatingAttributes.RPC_RESPONSE_STATUS_CODE;

import dev.tachyonmcp.core.server.observability.CapturedPayload;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Records an OpenTelemetry {@code SERVER} span and an {@code mcp.server.operation.duration}
 * measurement for every inbound MCP request and notification, following the OpenTelemetry MCP
 * semantic conventions — as a passive {@link ObservationListener}, with no ability to short-circuit,
 * reject, or substitute a handler's result.
 *
 * <pre>{@code
 * var server = TachyonServer.builder()
 *         .observability(o -> o.listener(McpOpenTelemetryListener.create(GlobalOpenTelemetry.get())))
 *         .build();
 * }</pre>
 *
 * <h2>Trace context</h2>
 *
 * <p>Spans are parented by {@link Context#current()} on whichever thread {@link #start} runs on.
 * Because the dispatcher only ever attaches this listener's {@link ObservationScope} around
 * synchronous dispatch work — decode, the call that kicks off an async handler, and (after
 * {@link ObservationScope#reattach()}) the handler's completion callback — a span an application
 * handler starts while dispatch is attached joins this trace as a child, without this listener ever
 * blocking an in-flight {@code CompletionStage} to keep the scope open.
 *
 * <h2>Payloads</h2>
 *
 * <p>Tool arguments are recorded as {@code gen_ai.tool.call.arguments} only when the server's {@code
 * PayloadCapturePolicy.requestArgs()} is enabled — this listener never captures payloads on its
 * own, it only reads what core already captured into {@link OperationInfo#requestPayload()}. Off by
 * default: arguments routinely carry credentials and personal data. Likewise, a handler/serialization
 * failure's exception is recorded as a span exception event (message and stack trace) only when
 * {@code PayloadCapturePolicy.exceptionDetail()} is enabled; core hands this listener a {@code null}
 * cause otherwise, and the span still gets an {@code ERROR} status with a generic message.
 *
 * @see <a href="https://github.com/open-telemetry/semantic-conventions-genai/tree/main/model/mcp">
 *     semantic-conventions-genai / model / mcp</a>
 */
public class McpOpenTelemetryListener implements ObservationListener {

    private static final String INSTRUMENTATION_NAME = "dev.tachyonmcp.opentelemetry";
    private static final String OPERATION_DURATION = "mcp.server.operation.duration";
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** MCP's wire protocol is JSON-RPC 2.0, unconditionally. */
    private static final String JSONRPC_VERSION_2_0 = "2.0";

    /** This listener only instruments {@code tachyon-core}'s Streamable HTTP transport. */
    private static final String NETWORK_PROTOCOL_HTTP = "http";

    private final Tracer tracer;
    private final DoubleHistogram operationDuration;
    private final ConcurrentHashMap<OperationInfo, PendingOperation> pending = new ConcurrentHashMap<>();

    private McpOpenTelemetryListener(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.operationDuration = openTelemetry
                .getMeter(INSTRUMENTATION_NAME)
                .histogramBuilder(OPERATION_DURATION)
                .setUnit("s")
                .setDescription("MCP request or notification duration as observed on the receiver")
                .build();
    }

    /**
     * Creates a listener bound to the given OpenTelemetry instance.
     *
     * @param openTelemetry the OpenTelemetry instance supplying the tracer and meter
     * @return a new listener
     */
    public static McpOpenTelemetryListener create(OpenTelemetry openTelemetry) {
        return new McpOpenTelemetryListener(Objects.requireNonNull(openTelemetry, "openTelemetry cannot be null"));
    }

    @Override
    public ObservationScope start(OperationInfo info) {
        var span =
                tracer.spanBuilder(info.method()).setSpanKind(SpanKind.SERVER).startSpan();
        pending.put(info, new PendingOperation(span, System.nanoTime()));
        var context = Context.current().with(span);
        return new SpanScope(context, context.makeCurrent());
    }

    @Override
    public void complete(OperationInfo info, OperationOutcome outcome) {
        var op = pending.remove(info);
        if (op == null) {
            // Defensive: should not happen since start() always precedes complete() for the same
            // OperationInfo, but a listener must never throw into the dispatch path over its own
            // bookkeeping gap.
            return;
        }
        var span = op.span;
        var target = target(info);
        span.updateName(target == null ? info.method() : info.method() + " " + target);

        var shared = sharedAttributes(info, target);
        var metricAttributes = Attributes.builder().putAll(shared);
        span.setAllAttributes(shared);
        span.setAllAttributes(spanOnlyAttributes(info));
        recordOutcome(span, metricAttributes, info, outcome);
        span.end();
        var establishmentNanos = info.establishmentNanos();
        var elapsedNanos =
                establishmentNanos != null ? establishmentNanos - op.startNanos : System.nanoTime() - op.startNanos;
        operationDuration.record(elapsedNanos / NANOS_PER_SECOND, metricAttributes.build());
    }

    private static @Nullable String target(OperationInfo info) {
        return switch (info.method()) {
            case TOOLS_CALL, PROMPTS_GET -> info.target();
            default -> null;
        };
    }

    private static boolean isToolCall(OperationInfo info) {
        return TOOLS_CALL.equals(info.method());
    }

    /** Attributes shared by the span and the duration histogram. All low-cardinality. */
    private static Attributes sharedAttributes(OperationInfo info, @Nullable String target) {
        var builder = Attributes.builder()
                .put(MCP_METHOD_NAME, info.method())
                .put(JSONRPC_PROTOCOL_VERSION, JSONRPC_VERSION_2_0)
                .put(NETWORK_PROTOCOL_NAME, NETWORK_PROTOCOL_HTTP);
        if (target != null) {
            if (isToolCall(info)) {
                builder.put(GEN_AI_TOOL_NAME, target).put(GEN_AI_OPERATION_NAME, EXECUTE_TOOL);
            } else {
                builder.put(GEN_AI_PROMPT_NAME, target);
            }
        }
        var protocolVersion = info.protocolVersion();
        if (protocolVersion != null) {
            builder.put(MCP_PROTOCOL_VERSION, protocolVersion);
        }
        var serverAddress = info.serverAddress();
        if (serverAddress != null) {
            builder.put(SERVER_ADDRESS, serverAddress);
        }
        var serverPort = info.serverPort();
        if (serverPort != null) {
            builder.put(SERVER_PORT, serverPort.longValue());
        }
        return builder.build();
    }

    /** Identifying attributes that belong on a span but would explode a metric's cardinality. */
    private static Attributes spanOnlyAttributes(OperationInfo info) {
        var builder = Attributes.builder();
        var requestId = info.requestId();
        if (requestId != null) {
            builder.put(JSONRPC_REQUEST_ID, requestId.toString());
        }
        var sessionId = info.sessionId();
        if (sessionId != null) {
            builder.put(MCP_SESSION_ID, sessionId);
        }
        return builder.build();
    }

    private void recordOutcome(
            Span span, AttributesBuilder metricAttributes, OperationInfo info, OperationOutcome outcome) {
        switch (outcome) {
            case OperationOutcome.Completed ignored -> recordToolCall(span, info);
            case OperationOutcome.TaskHandoff ignored -> {
                var requestPayload = info.requestPayload();
                if (requestPayload != null) {
                    recordArguments(span, requestPayload);
                }
            }
            case OperationOutcome.Rejected rejected -> {
                var error = rejected.error();
                if (error != null) {
                    classify(span, metricAttributes, error.kind().name());
                    var code = String.valueOf(rejected.wireCode());
                    span.setAttribute(RPC_RESPONSE_STATUS_CODE, code);
                    metricAttributes.put(RPC_RESPONSE_STATUS_CODE, code);
                    span.setStatus(StatusCode.ERROR, error.message());
                }
            }
            case OperationOutcome.PayloadFailure ignored -> {
                classify(span, metricAttributes, TOOL_ERROR);
                recordToolCall(span, info);
                // A JSON-RPC success -- no status code -- but error.type is present, so per the MCP
                // semconv the span status is still ERROR.
                span.setStatus(StatusCode.ERROR, "Tool call returned an error result");
            }
            case OperationOutcome.HandlerFailed failed -> {
                var code = String.valueOf(failed.wireCode());
                span.setAttribute(RPC_RESPONSE_STATUS_CODE, code);
                metricAttributes.put(RPC_RESPONSE_STATUS_CODE, code);
                classify(span, metricAttributes, failed.error().kind().name());
                if (failed.cause() != null) {
                    span.recordException(unwrap(failed.cause()));
                }
                span.setStatus(StatusCode.ERROR, failed.error().message());
            }
            case OperationOutcome.SerializationFailed serializationFailed -> {
                var cause = serializationFailed.cause();
                if (cause != null) {
                    span.recordException(cause);
                    span.setStatus(StatusCode.ERROR, Objects.toString(cause.getMessage(), ""));
                } else {
                    span.setStatus(StatusCode.ERROR, "Serialization failed");
                }
                classify(span, metricAttributes, serializationFailed.causeType());
            }
            case OperationOutcome.Cancelled ignored -> {}
            case OperationOutcome.NotificationAccepted ignored -> {}
            case OperationOutcome.NotificationIgnored ignored -> {}
            case OperationOutcome.StreamFailed streamFailed -> {
                var cause = streamFailed.cause();
                if (cause != null) {
                    span.recordException(cause);
                    span.setStatus(StatusCode.ERROR, Objects.toString(cause.getMessage(), ""));
                } else {
                    span.setStatus(StatusCode.ERROR, "Subscription stream failed");
                }
                classify(span, metricAttributes, streamFailed.causeType());
            }
        }
    }

    private static void recordPayload(Span span, CapturedPayload payload) {
        if (payload instanceof CapturedPayload.Value(String json)) {
            span.setAttribute(GEN_AI_TOOL_CALL_RESULT, json);
        }
    }

    private static void recordArguments(Span span, CapturedPayload payload) {
        if (payload instanceof CapturedPayload.Value(String json)) {
            span.setAttribute(GEN_AI_TOOL_CALL_ARGUMENTS, json);
        }
    }

    private static void recordToolCall(Span span, OperationInfo info) {
        if (isToolCall(info)) {
            var requestPayload = info.requestPayload();
            if (requestPayload != null) {
                if (requestPayload instanceof CapturedPayload.Value(String json)) {
                    span.setAttribute(GEN_AI_TOOL_CALL_ARGUMENTS, json);
                }
            }
            var responsePayload = info.responsePayload();
            if (responsePayload != null) {
                if (responsePayload instanceof CapturedPayload.Value(String json)) {
                    span.setAttribute(GEN_AI_TOOL_CALL_RESULT, json);
                }
            }
        }
    }

    /** Strips the {@code CompletionException} an async handler's failure may still be wrapped in. */
    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : error;
    }

    private static void classify(Span span, AttributesBuilder metricAttributes, String errorType) {
        span.setAttribute(ERROR_TYPE, errorType);
        metricAttributes.put(ERROR_TYPE, errorType);
    }

    private record PendingOperation(Span span, long startNanos) {}

    /**
     * Adapts an OTel {@link Scope} to {@link ObservationScope}. {@link #reattach()} may be called
     * more than once across the operation's lifetime (once per phase that runs after an executor
     * hop); each call re-activates the same captured {@link Context} on whichever thread is running
     * that phase and replaces {@link #current} with the fresh scope, which {@link #close()} then
     * closes. Never invoked concurrently for the same operation — the dispatch chain sequences
     * phases — so the mutable field needs no synchronization.
     */
    private static final class SpanScope implements ObservationScope {

        private final Context context;
        private Scope current;

        SpanScope(Context context, Scope initial) {
            this.context = context;
            this.current = initial;
        }

        @Override
        public void close() {
            current.close();
        }

        @Override
        public ObservationScope reattach() {
            current = context.makeCurrent();
            return this;
        }
    }
}
