package dev.tachyonmcp.docs.kotlin

// snips-start: kotlin_typed_echo_server
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.Serializable

@Serializable
data class EchoRequest(
    val message: String,
)

@Serializable
data class EchoResponse(
    val reply: String,
)

fun main() {
    val server =
        TachyonServer(port = 8080) {
            info {
                name = "echo-server"
                version = "1.0"
            }
            network { host = "127.0.0.1" }
            json { serde = KxSerializationSerde.Default }
            typedTool<EchoRequest, EchoResponse>(
                name = "echo",
                description = "Echo message",
            ) { input ->
                EchoResponse(input.message)
            }
        }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
// snips-end: kotlin_typed_echo_server
