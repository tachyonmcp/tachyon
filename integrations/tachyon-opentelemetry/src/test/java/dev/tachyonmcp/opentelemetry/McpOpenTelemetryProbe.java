/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.opentelemetry;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestServers;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual probe (not run by the automated test suite): starts a real Tachyon server with {@link
 * McpOpenTelemetryListener} wired to a real OTLP exporter, drives the same scenarios {@code
 * McpOpenTelemetryListenerTest} asserts against in-memory, and exports them to whatever OTel
 * collector/backend is listening — for visual inspection in an actual OTel tool (Jaeger, Grafana
 * Tempo, the collector's own debug exporter, etc.) rather than an assertion.
 *
 * <p>Run with a collector already listening on the standard OTLP gRPC port (4317), e.g.:
 *
 * <pre>{@code
 * docker run --rm -p 4317:4317 -p 16686:16686 jaegertracing/all-in-one:latest
 * }</pre>
 *
 * <p>Then open Jaeger at {@code http://localhost:16686}, service {@code tachyon-opentelemetry-probe}. The
 * endpoint follows the standard {@code OTEL_EXPORTER_OTLP_ENDPOINT} env var, defaulting to {@code
 * http://localhost:4317} if unset.
 */
public final class McpOpenTelemetryProbe {

    private McpOpenTelemetryProbe() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(McpOpenTelemetryProbe.class);

    public static void main(String[] args) throws Exception {
        var resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, "tachyon-opentelemetry-probe")));

        var spanExporter = OtlpGrpcSpanExporter.builder().build();
        var metricExporter = OtlpGrpcMetricExporter.builder().build();

        try (var otel = OpenTelemetrySdk.builder()
                        .setTracerProvider(SdkTracerProvider.builder()
                                .setResource(resource)
                                .addSpanProcessor(
                                        BatchSpanProcessor.builder(spanExporter).build())
                                .build())
                        .setMeterProvider(SdkMeterProvider.builder()
                                .setResource(resource)
                                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                                        .setInterval(Duration.ofSeconds(1))
                                        .build())
                                .build())
                        .build();
                TachyonServer server = startServer(otel);
                var legacyClient = new Mcp20251125Client(server.port());
                var modernClient = new Mcp20260728Client(server.port())) {
            LOGGER.info("Exporting to OTLP endpoint (OTEL_EXPORTER_OTLP_ENDPOINT, default http://localhost:4317)");
            LOGGER.info("Service name: tachyon-opentelemetry-probe");
            LOGGER.info("initialize + notifications/initialized...");
            var sessionId = legacyClient.initialize();

            LOGGER.info("tools/call forecast (success)...");
            legacyClient.post(sessionId, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"forecast","arguments":{"city":"Berlin"}}}""");

            LOGGER.info("tools/call failing (domain payload failure)...");
            legacyClient.post(sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"failing","arguments":{}}}""");

            LOGGER.info("tools/call throwing (handler exception)...");
            legacyClient.post(sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"throwing","arguments":{}}}""");

            LOGGER.info("tools/call absent (caller fault, unknown tool)...");
            legacyClient.post(sessionId, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"absent","arguments":{}}}""");

            LOGGER.info("resources/read greeting...");
            legacyClient.post(sessionId, """
                {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"resource://greeting"}}""");

            exerciseSubscription(server, modernClient);
            exerciseStreamingLog(modernClient);
        } finally {
            LOGGER.info("Flushing and shutting down the OTel SDK...");
        }

        LOGGER.info("Done. Check your OTel tool for service 'tachyon-opentelemetry-probe'.");
    }

    private static TachyonServer startServer(OpenTelemetry otel) {
        return McpTestServers.start(
                builder -> builder.session(session -> session.enabled())
                        .capabilities(capabilities -> capabilities.tools(true).logging())
                        .observability(o -> o.listener(McpOpenTelemetryListener.create(otel))),
                server -> {
                    server.tools().register(tool -> tool.name("forecast"), (ctx, request) -> ToolResult.text("sunny"));
                    server.tools().register(tool -> tool.name("failing"), (ctx, request) -> ToolResult.error("nope"));
                    server.tools().register(tool -> tool.name("throwing"), (ctx, request) -> {
                        throw new IOException("🔥 boom");
                    });
                    server.tools().register(tool -> tool.name("logging"), (ctx, request) -> {
                        ctx.notifications().info("otel.probe", "streamed log");
                        return ToolResult.text("logged");
                    });
                    server.resources()
                            .register(
                                    resource -> resource.name("greeting").uri("resource://greeting"),
                                    (ctx, request) -> TextResourceContents.of(request.uri(), "hello", "text/plain"));
                });
    }

    private static void exerciseSubscription(TachyonServer server, Mcp20260728Client client) throws Exception {
        LOGGER.info("subscriptions/listen + notifications/tools/list_changed...");
        try (var stream = client.openPostStream(null, """
            {"jsonrpc":"2.0","id":50,"method":"subscriptions/listen",
             "params":{"notifications":{"toolsListChanged":true}}}
            """)) {
            stream.await(
                    frame -> frame.data().contains("notifications/subscriptions/acknowledged"), Duration.ofSeconds(5));
            server.tools()
                    .register(
                            tool -> tool.name("subscription-trigger"), (ctx, request) -> ToolResult.text("triggered"));
            stream.await(frame -> frame.data().contains("notifications/tools/list_changed"), Duration.ofSeconds(5));
        }
    }

    private static void exerciseStreamingLog(Mcp20260728Client client) throws Exception {
        LOGGER.info("tools/call logging + notifications/message...");
        try (var stream = client.openPostStream(null, """
            {"jsonrpc":"2.0","id":51,"method":"tools/call",
             "params":{"name":"logging","arguments":{},
                       "_meta":{"io.modelcontextprotocol/logLevel":"info"}}}
            """)) {
            stream.await(frame -> frame.data().contains("notifications/message"), Duration.ofSeconds(5));
            stream.await(frame -> frame.data().contains("\"result\""), Duration.ofSeconds(5));
        }
    }
}
