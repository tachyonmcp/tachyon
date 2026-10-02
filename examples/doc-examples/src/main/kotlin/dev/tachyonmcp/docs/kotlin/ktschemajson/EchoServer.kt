package dev.tachyonmcp.docs.kotlin.ktschemajson

// snips-start: ktschema_echo_server
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.kpavlov.kt.schema.Description

@Serializable
@SerialName("EchoRequest")
data class EchoRequest(
    @Description("Message to echo")
    val message: String,
)

@Serializable
@SerialName("EchoResponse")
data class EchoResponse(
    @Description("Response message")
    val reply: String,
)

fun main() {
    val server = buildServer {
        capabilities { tools { mode = Mode.ON } }
        network {
            host = "127.0.0.1"
            port = 8080
        }
        info {
            name = "echo-server"
            version = "1.0"
        }
        json { serde = KxSerializationSerde.Default }
        typedTool<EchoRequest, EchoResponse>(
            name = "echo",
            description = "Echo message",
        ) { input ->
            EchoResponse(input.message)
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
// snips-end: ktschema_echo_server
