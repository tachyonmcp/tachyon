package dev.tachyonmcp.docs.running.observability;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener;
import io.opentelemetry.api.OpenTelemetry;

final class PayloadCapture {

    private PayloadCapture() {}

    static TachyonServer start(OpenTelemetry openTelemetry) {
        var server = TachyonServer.builder()
                .port(0)
                .annotations(annotations -> annotations.register(new MyMcpServer.GreetingService()))
                // snips-start: otel_payload_capture
                .observability(o -> o
                    .listener(McpOpenTelemetryListener.create(openTelemetry))
                    .payloadCapture(p -> p.requestArgs(true).responseContent(true)))
                // snips-end: otel_payload_capture
                .build();
        server.start();
        return server;
    }
}
