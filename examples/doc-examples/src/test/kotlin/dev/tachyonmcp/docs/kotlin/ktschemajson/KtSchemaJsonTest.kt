package dev.tachyonmcp.docs.kotlin.ktschemajson

import com.example.weather.model.TemperatureUnit
import com.example.weather.spi.WeatherObservation
import dev.tachyonmcp.docs.kotlin.DOCUMENTED_PORT
import dev.tachyonmcp.docs.kotlin.ForkedMain
import dev.tachyonmcp.docs.kotlin.items
import dev.tachyonmcp.docs.kotlin.post
import dev.tachyonmcp.docs.kotlin.rpcResult
import dev.tachyonmcp.docs.kotlin.text
import dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse
import io.kotest.matchers.shouldBe
import net.javacrumbs.jsonunit.assertj.assertThatJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class KtSchemaJsonTest {
    private val stub =
        object : WeatherService {
            override fun currentWeather(
                city: String,
                unit: TemperatureUnit,
            ) = WeatherObservation(
                condition = "Sunny",
                temperature = if (unit == TemperatureUnit.Celsius) 21.5 else 70.7,
                temperatureUnit = unit,
                humidity = 40,
                windSpeed = 12.0,
            )
        }

    private fun JsonNode.tool(name: String): JsonNode = path("tools").items().first { it.path("name").asString() == name }

    @Test
    fun `echo main publishes generated schemas and the literal one`() {
        ForkedMain.start("dev.tachyonmcp.docs.kotlin.ktschemajson.EchoServerKt").use {
            val tools = rpcResult(DOCUMENTED_PORT, "tools/list")
            val echo = tools.tool("echo")
            echo.path("inputSchema").path("properties").path("message").path("description").asString() shouldBe
                "Message to echo"
            echo.path("outputSchema").path("properties").path("reply").path("description").asString() shouldBe
                "Response message"
            val reverse = tools.tool("reverse-echo")
            reverse.path("inputSchema").path("required").items().map { it.asString() } shouldBe listOf("message")
            reverse.has("outputSchema") shouldBe false

            val echoed = rpcResult(DOCUMENTED_PORT, "tools/call", """{"name":"echo","arguments":{"message":"Hello, MCP!"}}""")
            echoed.path("structuredContent").path("reply").asString() shouldBe "Hello, MCP!"
            echoed.text() shouldBe """{"reply":"Hello, MCP!"}"""
            rpcResult(DOCUMENTED_PORT, "tools/call", """{"name":"reverse-echo","arguments":{"message":"stressed"}}""")
                .text() shouldBe "desserts"

            for (tool in listOf("echo", "reverse-echo")) {
                for (arguments in listOf("{}", """{"message":1}""")) {
                    assertThatResponse(
                        post(DOCUMENTED_PORT, "tools/call", """{"name":"$tool","arguments":$arguments}"""),
                    ).hasStatus(400).isJsonRpcError().hasErrorCode(-32602)
                }
            }
        }
    }

    @Test
    fun `get-weather publishes the schemas shown in the docs`() {
        assembleServer(0, stub).use { server ->
            server.start()
            val docs =
                KtSchemaJsonTest::class.java
                    .getResourceAsStream("/dev/tachyonmcp/docs/kotlin/ktschemajson/tools-list.json")!!
                    .readAllBytes()
                    .decodeToString()

            val published = server.rpcResult("tools/list").path("tools")

            assertThatJson(published.toString()).isEqualTo(
                tools.jackson.databind.ObjectMapper().readTree(docs).path("result").path("tools").toString(),
            )
        }
    }

    @Test
    fun `get-weather maps the observation into the response model`() {
        assembleServer(0, stub).use { server ->
            server.start()

            val result =
                server.rpcResult(
                    "tools/call",
                    """{"name":"get-weather","arguments":{"city":"London","units":"Fahrenheit"}}""",
                )

            val structured = result.path("structuredContent")
            structured.path("city").asString() shouldBe "London"
            structured.path("temperature").asDouble() shouldBe 70.7
            structured.path("temperatureUnit").asString() shouldBe "Fahrenheit"
            structured.path("humidity").asInt() shouldBe 40
            result.text() shouldBe structured.toString()

            server
                .rpcResult("tools/call", """{"name":"get-weather","arguments":{"city":"Oslo"}}""")
                .path("structuredContent")
                .path("temperatureUnit")
                .asString() shouldBe "Celsius"
        }
    }

    @Test
    fun `city elicitation schema is generated from the data class`() {
        val schema = citySchema().json()

        assertThatJson(schema).node("properties.city.type").isEqualTo("string")
        assertThatJson(schema).node("required").isEqualTo("""["city"]""")
    }
}
