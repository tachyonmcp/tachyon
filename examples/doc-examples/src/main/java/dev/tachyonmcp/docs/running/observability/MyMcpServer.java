package dev.tachyonmcp.docs.running.observability;

// snips-start: otel_server
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;

public final class MyMcpServer {
    public static final class GreetingService {
        @McpTool(description = "Say hello to someone")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    public static void main(String[] args) {
        final var openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                        .build())
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(PeriodicMetricReader.builder(LoggingMetricExporter.create())
                                .setInterval(Duration.ofSeconds(10))
                                .build())
                        .build())
                .build();

        final var server = TachyonServer.builder()
                .name("my-server")
                .version("1.0")
                .annotations(annotations -> annotations.register(new GreetingService()))
                .observability(o -> o.listener(McpOpenTelemetryListener.create(openTelemetry)))
                .host("127.0.0.1")
                .port(8080)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            openTelemetry.close();
        }));
        server.start();
    }
}
// snips-end: otel_server
