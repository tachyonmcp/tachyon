package dev.tachyonmcp.docs.running.observability

import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener
import io.opentelemetry.sdk.OpenTelemetrySdk

fun main() {
    val openTelemetry = OpenTelemetrySdk.builder().build()
    // snips-start: otel_kotlin_listener
    TachyonServer(port = 8080) {
        observability {
            listener(McpOpenTelemetryListener.create(openTelemetry))
        }
    }
    // snips-end: otel_kotlin_listener
}
