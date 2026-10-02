package dev.tachyonmcp.docs.running.configuration

import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.kotlin.server.TachyonServer

fun main() {
    // snips-start: config_kotlin_capabilities
    TachyonServer(port = 8080) {
        capabilities {
            tools { mode = Mode.ON; listChanged = true }
            resources { mode = Mode.ON; subscribe = true }
            completionsMode = Mode.ON
            logging = true
        }
    }
    // snips-end: config_kotlin_capabilities
}
