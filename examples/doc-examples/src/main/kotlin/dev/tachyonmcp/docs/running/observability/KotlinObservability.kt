package dev.tachyonmcp.docs.running.observability

import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener
import io.opentelemetry.api.OpenTelemetry

internal fun TachyonServerBuilder.captureConfig(openTelemetry: OpenTelemetry) {
    // snips-start: otel_kotlin_payload
    observability {
        listener(McpOpenTelemetryListener.create(openTelemetry))
        payloadCapture {
            requestArgs = true
            responseContent = true
        }
    }
    // snips-end: otel_kotlin_payload
}
