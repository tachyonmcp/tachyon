package dev.tachyonmcp.docs.kotlin

// snips-start: kotlin_demo_server
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun main() {
    val server =
        TachyonServer(port = 8080) {
            info {
                name = "demo-server"
                version = "1.0"
                description = "Demo MCP server"
            }
            capabilities {
                tools { listChanged = true }
                resources {
                    subscribe = true
                    listChanged = true
                }
                prompts { listChanged = true }
            }
            session {
                sessionTtl = 5.minutes
                sessionIdGenerator {
                    _,
                    _,
                    ->
                    "sess_" + UUID.randomUUID().toString().replace("-", "")
                }
            }
            tool(name = "ping", description = "Ping the server") {
                ToolResult.text("pong")
            }
            runtime {
                shutdownGracePeriod = 5.seconds
            }
            resource(
                name = "config",
                uri = "demo://config",
                description = "Server configuration",
                mimeType = "application/json",
            ) {
                TextResourceContents {
                    uri = this@resource.uri
                    text = """{"env":"prod"}"""
                    mimeType = "application/json"
                }
            }
            prompt(name = "greet", description = "Greeting prompt") {
                listOf(PromptMessage.user("Say hello, ${arguments.stringOr("name", "world")}"))
            }
        }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
// snips-end: kotlin_demo_server
