/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather

import com.example.weather.service.WeatherService
import dev.tachyonmcp.core.server.TachyonServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest
import io.modelcontextprotocol.spec.McpSchema.ElicitResult
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest
import io.modelcontextprotocol.spec.McpSchema.InitializeResult
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification
import io.modelcontextprotocol.spec.McpSchema.PromptReference
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest
import io.modelcontextprotocol.spec.McpSchema.ResourceReference
import io.modelcontextprotocol.spec.McpSchema.Role
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class WeatherServerTest {
    companion object {
        private val weatherProvider = TestWeatherProvider()
        private val cityProvider = TestCityProvider()
        private val weatherService = WeatherService(weatherProvider, cityProvider)
        private lateinit var server: TachyonServer
        private lateinit var clientTransport: HttpClientStreamableHttpTransport
        private lateinit var client: McpSyncClient
        private lateinit var initResult: InitializeResult
        private val progressNotifications = CopyOnWriteArrayList<ProgressNotification>()

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            server = assembleServer(0, weatherService)
            server.start()
            val port = server.port()

            clientTransport =
                HttpClientStreamableHttpTransport.builder("http://localhost:$port").build()
            client =
                McpClient
                    .sync(clientTransport)
                    .elicitation { _ ->
                        ElicitResult(
                            ElicitResult.Action.ACCEPT,
                            mapOf("city" to "Tallinn"),
                        )
                    }.progressConsumer { progressNotifications.add(it) }
                    .build()

            initResult = client.initialize()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            client.close()
            clientTransport.close()
            server.close()
        }
    }

    @Test
    fun verifyInitResult() {
        with(initResult.serverInfo()) {
            name() shouldBe "weather-server-kotlin"
            title() shouldBe "Weather Server (Kotlin)"
            description() shouldBe "Weather MCP server built with Tachyon Kotlin DSL"
            websiteUrl() shouldBe
                "https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp-kotlin"
            icons() shouldHaveSize 1
            icons().first().src() shouldStartWith "data:image/png;base64,"
        }
        initResult.protocolVersion() shouldBe "2025-11-25"
        initResult.instructions() shouldBe "Test instructions"
    }

    @Test
    fun `should list tools`() {
        val result = client.listTools()

        result.tools() shouldHaveSize 1
        result.tools().first() shouldNotBeNull {
            name() shouldBe "get-weather"
            title() shouldBe "Current Weather"
            description() shouldBe "Get current weather for a city"
            outputSchema() shouldNotBe null
        }
    }

    @Test
    fun `should call weather tool`() {
        val result =
            client.callTool(
                CallToolRequest
                    .builder("get-weather")
                    .arguments(mapOf("city" to "London", "units" to "Celsius"))
                    .build(),
            )

        result.isError shouldNotBe true
        result.structuredContent() shouldBe
            mapOf(
                "city" to "London",
                "condition" to "Clear sky",
                "temperature" to 18.5,
                "temperatureUnit" to "Celsius",
                "humidity" to 52,
                "windSpeed" to 12.0,
            )
        result
            .content()
            .single()
            .shouldBeInstanceOf<TextContent>()
            .text() shouldBe
            """{"city":"London","condition":"Clear sky","temperature":18.5,"temperatureUnit":"Celsius","humidity":52,"windSpeed":12.0}"""
    }

    @Test
    fun `should emit progress while fetching weather`() {
        val result =
            client.callTool(
                CallToolRequest
                    .builder("get-weather")
                    .arguments(mapOf("city" to "London"))
                    .progressToken("weather-progress")
                    .build(),
            )

        result.isError shouldNotBe true
        await atMost Duration.ofSeconds(5) untilAsserted {
            progressNotifications shouldHaveSize 2
        }
        progressNotifications.map { it.progressToken() to it.message() } shouldContainExactly
            listOf(
                "weather-progress" to "Fetching weather for London",
                "weather-progress" to "Weather retrieved for London",
            )
    }

    @Test
    fun `should call weather tool after eliciting another city`() {
        val result =
            client.callTool(
                CallToolRequest
                    .builder("get-weather")
                    .arguments(mapOf("city" to "Unknown"))
                    .build(),
            )

        result.isError shouldNotBe true
        result.structuredContent() shouldBe
            mapOf(
                "city" to "Tallinn",
                "condition" to "Clear sky",
                "temperature" to 18.5,
                "temperatureUnit" to "Celsius",
                "humidity" to 52,
                "windSpeed" to 12.0,
            )
        result
            .content()
            .single()
            .shouldBeInstanceOf<TextContent>()
            .text() shouldBe
            """{"city":"Tallinn","condition":"Clear sky","temperature":18.5,"temperatureUnit":"Celsius","humidity":52,"windSpeed":12.0}"""
    }

    @Test
    fun `should list resources`() {
        val result = client.listResources()

        result.resources() shouldHaveSize 2
        val article = result.resources().first { it.uri() == "weather://prediction/article" }
        with(article) {
            name() shouldBe "prediction-article"
            title() shouldBe "Weather Prediction"
            description() shouldBe "Weather prediction article"
            mimeType() shouldBe "text/markdown"
            size() shouldBe
                weatherService.predictionArticle
                    .toByteArray()
                    .size
                    .toLong()
            with(annotations()) {
                audience() shouldContainExactly
                    listOf(Role.USER, Role.ASSISTANT)
                priority() shouldBe 0.8
                lastModified() shouldBe "2026-07-23T00:00:00Z"
            }
            with(icons().single()) {
                src() shouldStartWith "data:image/png;base64,"
                mimeType() shouldBe "image/png"
                sizes() shouldContainExactly listOf("256x256")
                theme() shouldBe "light"
            }
        }

        val weather =
            result.resources().first {
                it.uri() == "weather://featured/current"
            }
        with(weather) {
            name() shouldBe "featured-current-weather"
            title() shouldBe "Featured Current Weather"
            description() shouldBe "Current weather in Tallinn"
            mimeType() shouldBe "application/json"
            annotations() shouldBe article.annotations()
            icons() shouldBe article.icons()
        }
    }

    @Test
    fun `should read text resource`() {
        val article =
            client.listResources().resources().first { it.uri() == "weather://prediction/article" }

        val result = client.readResource(article)

        val contents = result.contents().first()
        with(contents) {
            shouldBeInstanceOf<TextResourceContents>()
            uri() shouldBe "weather://prediction/article"
            mimeType() shouldBe "text/markdown"
            text().trim() shouldStartWith "# Weather Prediction"
        }
    }

    @Test
    fun `should read current weather resource`() {
        val weather =
            client.listResources().resources().first { it.uri() == "weather://featured/current" }

        val result = client.readResource(weather)

        val contents = result.contents().first()
        with(contents) {
            shouldBeInstanceOf<TextResourceContents>()
            uri() shouldBe "weather://featured/current"
            mimeType() shouldBe "application/json"
            text() shouldContain "Clear sky"
        }
    }

    @Test
    fun `should list resource templates`() {
        val result = client.listResourceTemplates()

        result.resourceTemplates() shouldHaveSize 1
        result.resourceTemplates().first() shouldNotBeNull {
            uriTemplate() shouldBe "weather://current/{city}"
            name() shouldBe "current-weather"
            mimeType() shouldBe "application/json"
        }
    }

    @Test
    fun `should read current weather from template`() {
        val result =
            client.readResource(
                ReadResourceRequest.builder("weather://current/London").build(),
            )

        val contents = result.contents().first()
        contents.shouldBeInstanceOf<TextResourceContents>()
        contents.uri() shouldBe "weather://current/London"
        contents.mimeType() shouldBe "application/json"
        contents.text() shouldContain "Clear sky"
    }

    @Test
    fun `should return invalid params when template city is unknown`() {
        val error =
            shouldThrow<McpError> {
                client.readResource(
                    ReadResourceRequest.builder("weather://current/Unknown").build(),
                )
            }
        error.jsonRpcError.code() shouldBe -32602
    }

    @Test
    fun `should complete city name for current weather template`() {
        val result =
            client.completeCompletion(
                CompleteRequest
                    .builder(
                        ResourceReference("weather://current/{city}"),
                        CompleteRequest.CompleteArgument("city", "Lo"),
                    ).build(),
            )

        result.completion().values() shouldContainExactlyInAnyOrder listOf("London", "Los Angeles")
        result.completion().hasMore() shouldNotBe true
    }

    @Test
    fun `should return empty completion for blank query`() {
        val result =
            client.completeCompletion(
                CompleteRequest
                    .builder(
                        ResourceReference("weather://current/{city}"),
                        CompleteRequest.CompleteArgument("city", ""),
                    ).build(),
            )

        result.completion().values() shouldHaveSize 0
    }

    @Test
    fun `should complete style name for rewrite-forecast prompt`() {
        val result =
            client.completeCompletion(
                CompleteRequest
                    .builder(
                        PromptReference("rewrite-forecast"),
                        CompleteRequest.CompleteArgument("style", "pi"),
                    ).build(),
            )

        result.completion().values() shouldContainExactly listOf("pirate")
    }

    @Test
    fun `should return empty completion for non-style argument of rewrite-forecast prompt`() {
        val result =
            client.completeCompletion(
                CompleteRequest
                    .builder(
                        PromptReference("rewrite-forecast"),
                        CompleteRequest.CompleteArgument("forecast", "Rain"),
                    ).build(),
            )

        result.completion().values() shouldHaveSize 0
    }

    @Test
    fun `should list prompts`() {
        val result = client.listPrompts()

        result.prompts() shouldHaveSize 1
        val prompt = result.prompts().first()
        prompt.name() shouldBe "rewrite-forecast"
        prompt.description() shouldBe "Rewrites a weather forecast in a chosen style"
        prompt.arguments() shouldHaveSize 2
    }

    @Test
    fun `should get prompt`() {
        val result =
            client.getPrompt(
                GetPromptRequest
                    .builder("rewrite-forecast")
                    .arguments(mapOf("forecast" to "Rain in London", "style" to "pirate"))
                    .build(),
            )

        result.messages() shouldHaveSize 1
        val message = result.messages().first()
        message.role() shouldBe Role.USER
        message.content().shouldBeInstanceOf<TextContent>()
        (message.content() as TextContent).text() shouldBe
            "Rewrite the following weather forecast in pirate style. Preserve factual details:\n\n```Rain in London\n```"
    }
}
