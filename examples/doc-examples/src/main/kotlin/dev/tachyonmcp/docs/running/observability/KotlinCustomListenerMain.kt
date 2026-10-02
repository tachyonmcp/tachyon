package dev.tachyonmcp.docs.running.observability

import dev.tachyonmcp.kotlin.server.TachyonServer

fun main() {
    val myListener = RecordingListener()
    // snips-start: otel_kotlin_custom_listener
    TachyonServer(port = 8080) {
        observability {
            listener(myListener)
        }
    }
    // snips-end: otel_kotlin_custom_listener
}
