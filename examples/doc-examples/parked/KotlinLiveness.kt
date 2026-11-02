package dev.tachyonmcp.docs.running.configuration

import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder

internal fun TachyonServerBuilder.livenessProbe() {
    // snips-start: config_kotlin_liveness
    network { liveness() } // or liveness("/health/live")
    // snips-end: config_kotlin_liveness
}
