package dev.tachyonmcp.docs.running.configuration

import dev.tachyonmcp.kotlin.server.TachyonServer
import kotlin.time.Duration.Companion.seconds

fun main() {
    // snips-start: config_kotlin_observability
    TachyonServer(port = 8080) {
        observability {
            slowRequestLogging(threshold = 5.seconds)
        }
    }
    // snips-end: config_kotlin_observability
}
