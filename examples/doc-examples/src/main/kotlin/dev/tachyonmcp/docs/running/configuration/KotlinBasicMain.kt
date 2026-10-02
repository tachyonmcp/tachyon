package dev.tachyonmcp.docs.running.configuration

import dev.tachyonmcp.kotlin.server.TachyonServer
import kotlin.time.Duration.Companion.minutes

fun main() {
    // snips-start: config_kotlin_basic
    val server = TachyonServer {
        info { name = "my-server"; version = "1.0" }
        network { port = 8080 }
        session { sessionTtl = 5.minutes }
    }
    // snips-end: config_kotlin_basic
}
