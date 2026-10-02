package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder
import dev.tachyonmcp.kotlin.server.domain.decode
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import dev.tachyonmcp.kotlin.server.json.ktschema.ktSchemaGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig

internal fun TachyonServerBuilder.toolSchemaShapes() {
    // snips-start: kotlin_tool_schema_shapes
    // JsonSchema — parse a JSON string, or generate one from a class
    tool(
        "a",
        inputSchema =
            JsonSchema.parse(
                """{"type":"object","properties":{"msg":{"type":"string"}}}""",
            ),
        outputSchema =
            JsonSchema.parse(
                """{"type":"object","properties":{"echo":{"type":"string"}}}""",
            ),
    ) { text("a") }

    // kotlinx.serialization JsonObject — requires kotlinx-serialization-json (optional)
    tool("b", inputSchema = buildJsonObject { put("type", "object") }) { text("b") }
    // snips-end: kotlin_tool_schema_shapes
}

internal fun registerToolWithJsonString(server: TachyonServer) {
    // snips-start: kotlin_tool_schema_json_string
    server.registerTool(
        "c",
        inputSchema = """{"type":"object","properties":{"msg":{"type":"string"}}}""",
        outputSchema = """{"type":"object","properties":{"echo":{"type":"string"}}}""",
    ) { text("c") }
    // snips-end: kotlin_tool_schema_json_string
}

internal fun TachyonServerBuilder.typedToolWithSchemaGenerator() {
    // snips-start: kotlin_typed_tool_schema_generator
    typedTool<EchoRequest, EchoResponse>(
        name = "echo",
        schemaGenerator = ktSchemaGenerator(JsonSchemaConfig.Default),
    ) { input -> EchoResponse(input.message) }
    // snips-end: kotlin_typed_tool_schema_generator
}

// snips-start: kotlin_serialization_types
@Serializable
data class EchoArgs(
    val message: String,
    val loud: Boolean = false,
)

@Serializable
data class EchoReply(
    val echo: String,
)
// snips-end: kotlin_serialization_types

internal fun TachyonServerBuilder.kotlinxTool() {
    // snips-start: kotlin_serialization_tool
    json { serde = KxSerializationSerde.Default }

    tool(
        "echo",
        inputSchema =
            JsonSchema.parse(
                """{"type":"object","properties":{"message":{"type":"string"}}}""",
            ),
        outputSchema =
            JsonSchema.parse(
                """{"type":"object","properties":{"echo":{"type":"string"}}}""",
            ),
    ) {
        val input = arguments.decode<EchoArgs>() // typed decode via configured serde
        success(EchoReply(input.message)) // structuredContent via configured serde
    }
    // snips-end: kotlin_serialization_tool
}

// snips-start: kotlin_greet_types
@Serializable
data class GreetArgs(
    val name: String,
    val greeting: String = "Hello",
)

@Serializable
data class GreetReply(
    val message: String,
)
// snips-end: kotlin_greet_types

internal fun TachyonServerBuilder.greetTool() {
    // snips-start: kotlin_greet_tool
    tool(
        name = "greet",
        inputSchema =
            JsonSchema.parse(
                """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
            ),
        outputSchema =
            JsonSchema.parse(
                """{"type":"object","properties":{"message":{"type":"string"}}}""",
            ),
    ) {
        val input = arguments.decode<GreetArgs>() // honors configured serde
        success(
            GreetReply("${input.greeting}, ${input.name}!"),
            "greeting response",
        ) // symmetric typed result
    }
    // snips-end: kotlin_greet_tool
}
