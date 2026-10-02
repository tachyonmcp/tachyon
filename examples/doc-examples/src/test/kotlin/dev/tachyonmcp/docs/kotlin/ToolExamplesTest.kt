package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import dev.tachyonmcp.kotlin.server.json.ktschema.ktSchemaGenerator
import dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

@Serializable data class WeatherQuery(val city: String, val units: String = "metric")

@Serializable data class Forecast(val summary: String)

class ToolExamplesTest {
    private fun JsonNode.tool(name: String): JsonNode = path("tools").items().first { it.path("name").asString() == name }

    private fun JsonNode.required(): List<String> = path("inputSchema").path("required").items().map { it.asString() }

    @Test
    fun `tool accepts a JsonSchema and a kotlinx JsonObject`() {
        TachyonServer(port = 0) { toolSchemaShapes() }.use { server ->
            val tools = server.rpcResult("tools/list")

            tools.names("tools") shouldContainExactlyInAnyOrder listOf("a", "b")
            val a = tools.tool("a")
            a.path("inputSchema").path("properties").path("msg").path("type").asString() shouldBe "string"
            a.path("outputSchema").path("properties").path("echo").path("type").asString() shouldBe "string"
            tools.tool("b").path("inputSchema").path("type").asString() shouldBe "object"
            server.rpcResult("tools/call", """{"name":"b","arguments":{}}""").text() shouldBe "b"
        }
    }

    @Test
    fun `registerTool takes JSON strings and the builder string overload is the deprecated one`() {
        TachyonServer(port = 0) { }.use { server ->
            registerToolWithJsonString(server)

            val c = server.rpcResult("tools/list").tool("c")
            c.path("inputSchema").path("properties").path("msg").path("type").asString() shouldBe "string"
            c.path("outputSchema").path("properties").path("echo").path("type").asString() shouldBe "string"
        }
    }

    @Test
    fun `input schema root must be an object but output schema may be any root`() {
        shouldThrow<IllegalArgumentException> {
            buildServer {
                tool("x", inputSchema = JsonSchema.parse("""{"type":"array"}""")) { text("x") }
            }
        }.message shouldContain "inputSchema root must declare \"type\": \"object\""

        buildServer {
            tool(
                "arr",
                inputSchema = JsonSchema.parse("""{"type":"object"}"""),
                outputSchema = JsonSchema.parse("""{"type":"array","items":{"type":"string"}}"""),
            ) { text("x") }
        }.close()
    }

    @Test
    fun `typedTool with a generator derives both schemas and returns structured content`() {
        TachyonServer(port = 0) {
            json { serde = KxSerializationSerde.Default }
            typedToolWithSchemaGenerator()
        }.use { server ->
            val echo = server.rpcResult("tools/list").tool("echo")
            echo.required() shouldContainExactly listOf("message")
            echo.path("inputSchema").path("properties").path("message").path("type").asString() shouldBe "string"
            echo.path("outputSchema").path("properties").path("reply").path("type").asString() shouldBe "string"

            val result = server.rpcResult("tools/call", """{"name":"echo","arguments":{"message":"Hello, MCP!"}}""")
            result.path("structuredContent").path("reply").asString() shouldBe "Hello, MCP!"
            result.text() shouldBe """{"reply":"Hello, MCP!"}"""
        }
    }

    @Test
    fun `a Kotlin default only becomes optional with the kt-schema default config`() {
        TachyonServer(port = 0) {
            json { serde = KxSerializationSerde.Default }
            typedTool<WeatherQuery, Forecast>(name = "out-of-the-box") { Forecast(it.city) }
            typedTool<WeatherQuery, Forecast>(
                name = "lenient",
                schemaGenerator = ktSchemaGenerator(JsonSchemaConfig.Default),
            ) { Forecast(it.city) }
        }.use { server ->
            val tools = server.rpcResult("tools/list")
            tools.tool("out-of-the-box").required() shouldContainExactlyInAnyOrder listOf("city", "units")
            tools.tool("lenient").required() shouldContainExactly listOf("city")

            assertThatResponse(server.post("tools/call", """{"name":"out-of-the-box","arguments":{"city":"Oslo"}}"""))
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32602)
            server.rpcResult("tools/call", """{"name":"lenient","arguments":{"city":"Oslo"}}""")
                .path("structuredContent")
                .path("summary")
                .asString() shouldBe "Oslo"
        }
    }

    @Test
    fun `decode and success go through the configured kotlinx serde`() {
        TachyonServer(port = 0) { kotlinxTool() }.use { server ->
            val result = server.rpcResult("tools/call", """{"name":"echo","arguments":{"message":"hi"}}""")

            result.path("structuredContent").path("echo").asString() shouldBe "hi"
            result.text() shouldBe """{"echo":"hi"}"""
        }
    }

    @Test
    fun `greet decodes defaults and pairs a typed result with explicit text`() {
        TachyonServer(port = 0) {
            json { serde = KxSerializationSerde.Default }
            greetTool()
        }.use { server ->
            val greeting = server.rpcResult("tools/call", """{"name":"greet","arguments":{"name":"Ada"}}""")
            greeting.path("structuredContent").path("message").asString() shouldBe "Hello, Ada!"
            greeting.text() shouldBe "greeting response"

            server.rpcResult("tools/call", """{"name":"greet","arguments":{"name":"Ada","greeting":"Hi"}}""")
                .path("structuredContent")
                .path("message")
                .asString() shouldBe "Hi, Ada!"
        }
    }

    @Test
    fun `the default Jackson serde cannot decode Kotlin data classes`() {
        TachyonServer(port = 0) { greetTool() }.use { server ->
            assertThatResponse(server.post("tools/call", """{"name":"greet","arguments":{"name":"Ada"}}"""))
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32602)
                .hasErrorMessageContaining("could not be decoded")
        }
    }
}
