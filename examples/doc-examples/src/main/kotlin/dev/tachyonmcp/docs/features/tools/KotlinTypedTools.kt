package dev.tachyonmcp.docs.features.tools

// snips-start: tools_kotlin_typed_types
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.kotlin.server.domain.decode
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.Serializable

@Serializable
data class EchoArgs(val message: String)
@Serializable
data class EchoReply(val echo: String)
// snips-end: tools_kotlin_typed_types

internal fun dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder.typedEchoTool() {
    // snips-start: tools_kotlin_typed_tool
    json { serde = KxSerializationSerde.Default }

    tool(
        "echo",
        inputSchema = JsonSchema.parse(
            """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""",
        ),
        outputSchema = JsonSchema.parse(
            """{"type":"object","properties":{"echo":{"type":"string"}},"required":["echo"]}""",
        ),
    ) {
        val input = arguments.decode<EchoArgs>()
        success(EchoReply(input.message))
    }
    // snips-end: tools_kotlin_typed_tool
}
