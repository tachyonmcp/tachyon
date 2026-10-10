// Copyright (c) 2026 Konstantin Pavlov and contributors.

package com.example.echo

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer

fun assembleServer(
    port: Int = 0,
    host: String = "localhost",
    allowedHost: String? = null,
): TachyonServer {
    val server =
        buildServer {
            network {
                this.host = host
                this.port = port
                if (!allowedHost.isNullOrBlank()) allowedHosts += allowedHost
            }
            info {
                name = "echo-server"
                title = "Echo Server"
                version = "1.0.1"
                description = "Echo MCP server built with Tachyon Kotlin DSL"
            }
            capabilities {
                tools { mode = Mode.ON }
            }
            typedTool<EchoRequest, EchoResponse>(
                name = "echo",
                description = "Echo message",
            ) { input ->
                ToolResult.structured(
                    EchoResponse(input.message),
                )
            }
        }

    server.registerTool(
        name = "reverse-echo",
        description = "Echo reverse message",
        inputSchema =
            JsonSchema.unchecked(
                // language=json
                """
                {
                  "type": "object",
                  "properties": {
                    "message": {
                      "type": "string",
                      "description": "Message to echo"
                    }
                  },
                  "required": ["message"]
                }
        """,
            ),
    ) {
        text(arguments.stringValue("message").reversed())
    }
    return server
}

fun main() {
    val server =
        assembleServer(
            port = System.getenv("PORT")?.toInt() ?: 8080,
            host = System.getenv("HOST") ?: "localhost",
            allowedHost = System.getenv("ALLOWED_HOST"),
        )
    server.start()
    println(
        "Echo server running. Connect your MCP client " +
            "to http://${server.host()}:${server.port()}/mcp",
    )
    Thread.sleep(Long.MAX_VALUE)
}
