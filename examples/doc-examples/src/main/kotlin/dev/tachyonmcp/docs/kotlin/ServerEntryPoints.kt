package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer

internal fun entryPoints() {
    // snips-start: kotlin_entry_points
    // Start Netty transport — returns the Kotlin TachyonServer
    val server = TachyonServer(port = 8080) { /* configure */ }

    // Server logic only, no transport — for testing
    val testServer: TachyonServer = buildServer { /* configure */ }
    // snips-end: kotlin_entry_points
}

internal fun ephemeralPortServer(): TachyonServer {
    // snips-start: kotlin_testing_server
    val server = TachyonServer(port = 0) { tool("ping") { ToolResult.text("pong") } }
    // server.host() → bound host, server.port() → ephemeral port
    // snips-end: kotlin_testing_server
    return server
}
