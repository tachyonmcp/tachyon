package dev.tachyonmcp.docs.kotlin.migratefromkotlinsdk

import dev.tachyonmcp.api.server.domain.TextResourceContents
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.docs.kotlin.items
import dev.tachyonmcp.docs.kotlin.post
import dev.tachyonmcp.docs.kotlin.rpcResult
import dev.tachyonmcp.docs.kotlin.text
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.testkit.Mcp20251125Client
import dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse
import dev.tachyonmcp.testkit.McpTestClients
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

@Serializable
private data class DefaultsReply(
    val value: String = "default",
)

class MigrateFromKotlinSdkTest {
    private val ping = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""

    @Test
    fun `identity icons capabilities and origins are ported`() {
        configuredServer(0, "9.9", Json).use { server ->
            registerSearch(server)
            server.resources().register(
                { descriptor -> descriptor.name("note").uri("example://note") },
                { _, request -> TextResourceContents.of(request.uri(), "note", "text/plain") },
            )
            val discover = server.rpcResult("server/discover")

            val info = discover.path("serverInfo")
            info.path("name").asString() shouldBe "example-server"
            info.path("title").asString() shouldBe "Example MCP"
            info.path("version").asString() shouldBe "9.9"
            info.path("websiteUrl").asString() shouldBe "https://example.com/docs"
            val icon = info.path("icons").get(0)
            icon.path("src").asString() shouldStartWith "data:image/png;base64,"
            icon.path("mimeType").asString() shouldBe "image/png"
            icon.path("sizes").get(0).asString() shouldBe "32x32"

            val capabilities = discover.path("capabilities")
            capabilities.path("tools").path("listChanged").asBoolean() shouldBe true
            capabilities.path("resources").path("listChanged").asBoolean() shouldBe true
            capabilities.path("resources").path("subscribe").asBoolean() shouldBe false
            capabilities.has("logging") shouldBe true

            McpTestClients.latest(server.port()).use { client ->
                assertThatResponse(client.postWithOrigin("https://your-client.example.com", ping)).hasStatus(200)
                assertThatResponse(client.postWithOrigin("https://evil.example.com", ping)).hasStatus(403)
            }
            McpTestClients.forVersion(server.port(), "2025-11-25").use { client ->
                (client.initialize() != null) shouldBe true

            }
        }
    }

    @Test
    fun `the configured json serde decides whether defaults are encoded`() {
        for ((json, expected) in listOf(Json { encodeDefaults = true } to """{"value":"default"}""", Json to "{}")) {
            configuredServer(0, "1", json).use { server ->
                server.registerTool(name = "reply", inputSchema = """{"type":"object"}""") {
                    success(DefaultsReply())
                }

                server
                    .rpcResult("tools/call", """{"name":"reply","arguments":{}}""")
                    .path("structuredContent")
                    .toString() shouldBe expected
            }
        }
    }

    @Test
    fun `lifecycle statements use the registries and stop the server`() {
        val server = configuredServer(0, "1", Json)
        val port = server.port()

        lifecycle(server)

        shouldThrow<java.io.IOException> {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        }
    }

    @Test
    fun `registerTool validates the input schema and returns the encoded result`() {
        TachyonServer(port = 0) {}.use { server ->
            registerSearch(server)

            val search = server.rpcResult("tools/list").path("tools").get(0)
            search.path("name").asString() shouldBe "search"
            search.path("description").asString() shouldBe "Search the index."
            search.path("outputSchema").path("properties").path("hits").path("type").asString() shouldBe "array"
            server.rpcResult("tools/call", """{"name":"search","arguments":{"query":"cats"}}""").text() shouldBe
                """{"hits":["cats-1","cats-2"]}"""
            assertThatResponse(server.post("tools/call", """{"name":"search","arguments":{}}"""))
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32602)
        }
    }

    @Test
    fun `handler logs reach the client through the interaction context`() {
        configuredServer(0, "1", Json).use { server ->
            server.registerTool(name = "log-it", inputSchema = """{"type":"object"}""") {
                logEntry("example", mapOf("event" to "indexed"))
                ToolResult.text("logged")
            }
            Mcp20251125Client(server.port()).use { client ->
                val sessionId = client.initialize()
                client.sendRpc("""{"jsonrpc":"2.0","id":2,"method":"logging/setLevel","params":{"level":"info"}}""")

                client
                    .openPostStream(
                        sessionId,
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"log-it","arguments":{}}}""",
                    ).use { stream ->
                        val log = stream.await({ it.data().contains("notifications/message") }, Duration.ofSeconds(5)).json()

                        log.path("params").path("level").asString() shouldBe "info"
                        log.path("params").path("logger").asString() shouldBe "example"
                        log.path("params").path("data").path("event").asString() shouldBe "indexed"
                    }
            }
        }
    }

    @Test
    fun `resources and templates inherit the requested uri and mime type`() {
        resourceServer(0).use { server ->
            val list = server.rpcResult("resources/list").path("resources").items()
            list.map { it.path("uri").asString() } shouldBe listOf("example://docs/readme")
            list[0].path("title").asString() shouldBe "README"
            server.rpcResult("resources/templates/list").path("resourceTemplates").items().map {
                it.path("uriTemplate").asString()
            } shouldBe listOf("example://docs/{path}")

            val readme = server.rpcResult("resources/read", """{"uri":"example://docs/readme"}""").path("contents").get(0)
            readme.path("text").asString() shouldBe "# README"
            readme.path("uri").asString() shouldBe "example://docs/readme"
            readme.path("mimeType").asString() shouldBe "text/markdown"

            val guide = server.rpcResult("resources/read", """{"uri":"example://docs/guide"}""").path("contents").get(0)
            guide.path("text").asString() shouldBe "# Guide"
            guide.path("uri").asString() shouldBe "example://docs/guide"
            guide.path("mimeType").asString() shouldBe "text/markdown"

            assertThatResponse(server.post("resources/read", """{"uri":"example://docs/missing"}""")).isJsonRpcError()
        }
    }

    @Test
    fun `a reusable template descriptor registers like the inline form`() {
        descriptorServer(0).use { server ->
            server.rpcResult("resources/templates/list").path("resourceTemplates").get(0).path("name").asString() shouldBe "docs"
            server
                .rpcResult("resources/read", """{"uri":"example://docs/guide"}""")
                .path("contents")
                .get(0)
                .path("text")
                .asString() shouldBe "# Guide"
        }
    }

    @Test
    fun `sendRequest asks the client and joins its answer`() {
        TachyonServer(port = 0) {
            session { enable() }
            tool(name = "ask") {
                askUser(mapOf("message" to "Pick a city", "requestedSchema" to mapOf("type" to "object")))
            }
        }.use { server ->
            Mcp20251125Client(server.port()).use { client ->
                val initialize =
                    client.post(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                        |"capabilities":{"elicitation":{"form":{}}},"clientInfo":{"name":"t","version":"1"}}}
                        """.trimMargin(),
                    )
                val sessionId = initialize.headers().firstValue("MCP-Session-Id").orElseThrow()
                client.sendInitialized(sessionId)

                client
                    .openPostStream(
                        sessionId,
                        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask","arguments":{}}}""",
                    ).use { stream ->
                        val request = stream.await({ it.data().contains("elicitation/create") }, Duration.ofSeconds(5)).json()
                        request.path("params").path("message").asString() shouldBe "Pick a city"
                        client.post(
                            sessionId,
                            """{"jsonrpc":"2.0","id":${request.path("id")},"result":{"action":"accept","content":{"city":"Paris"}}}""",
                        )

                        val answer = stream.await({ it.data().contains("\"id\":2") }, Duration.ofSeconds(5))

                        answer.data() shouldContain "Paris"
                    }
            }
        }
    }
}
