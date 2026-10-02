// snips-start: kotlin_reverse_echo_server
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.kotlin.server.buildServer

fun main() {
    val server = buildServer {
        capabilities { tools { mode = Mode.ON } }
        info {
            name = "echo-server"
            version = "1.0"
        }
        network {
            host = "127.0.0.1"
            port = 8080
        }
    }
    server.registerTool(
        name = "reverse-echo",
        description = "Echo reverse message",
        inputSchema = JsonSchema.unchecked(
            """
            {
              "type": "object",
              "properties": {
                "message": {"type": "string", "description": "Message to echo"}
              },
              "required": ["message"]
            }
            """,
        ),
    ) {
        text(arguments.stringValue("message").reversed())
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start()
}
// snips-end: kotlin_reverse_echo_server
