package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.testkit.Mcp20251125Client
import dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse
import dev.tachyonmcp.testkit.McpTestClients
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class MainServersTest {
    @Test
    fun `reverse-echo main reverses the message`() {
        ForkedMain.start("MyMcpServerKt").use {
            val tool = rpcResult(DOCUMENTED_PORT, "tools/list").path("tools").get(0)
            tool.path("name").asString() shouldBe "reverse-echo"
            tool.path("description").asString() shouldBe "Echo reverse message"
            tool.path("inputSchema").path("required").items().map { it.asString() } shouldBe listOf("message")
            tool.path("inputSchema").path("properties").path("message").path("type").asString() shouldBe "string"

            rpcResult(
                DOCUMENTED_PORT,
                "tools/call",
                """{"name":"reverse-echo","arguments":{"message":"stressed"}}""",
            ).text() shouldBe "desserts"
        }
    }

    @Test
    fun `demo main serves the tool, resource, prompt and sessions it configures`() {
        ForkedMain.start("dev.tachyonmcp.docs.kotlin.DemoServerKt").use {
            rpcResult(DOCUMENTED_PORT, "tools/call", """{"name":"ping","arguments":{}}""").text() shouldBe "pong"

            val config = rpcResult(DOCUMENTED_PORT, "resources/read", """{"uri":"demo://config"}""")
                .path("contents").get(0)
            config.path("text").asString() shouldBe """{"env":"prod"}"""
            config.path("mimeType").asString() shouldBe "application/json"

            rpcResult(DOCUMENTED_PORT, "prompts/get", """{"name":"greet","arguments":{"name":"Ada"}}""")
                .promptText() shouldBe "Say hello, Ada"
            rpcResult(DOCUMENTED_PORT, "prompts/get", """{"name":"greet"}""").promptText() shouldBe
                "Say hello, world"

            McpTestClients.latest(DOCUMENTED_PORT).use { client ->
                client.discover().isSuccess().hasCapabilities(
                    """{"tools":{"listChanged":true},"resources":{"listChanged":true},"prompts":{"listChanged":true}}""",
                )
            }
            McpTestClients.forVersion(DOCUMENTED_PORT, "2025-11-25").use { client ->
                val capabilities = assertThatResponse(
                    client.post(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
                    ),
                ).hasStatus(200).isSuccess().result().path("capabilities")

                capabilities.path("tools").path("listChanged").asBoolean() shouldBe true
                capabilities.path("resources").path("subscribe").asBoolean() shouldBe true
                capabilities.path("resources").path("listChanged").asBoolean() shouldBe true
                capabilities.path("prompts").path("listChanged").asBoolean() shouldBe true
            }

            (McpTestClients.builder(DOCUMENTED_PORT).protocolVersion("2025-11-25").build() as Mcp20251125Client)
                .use { client -> client.sessionId()!! shouldMatch Regex("sess_[0-9a-f]{32}") }
        }
    }

    @Test
    fun `typed echo main returns the decoded request as structured content`() {
        ForkedMain.start("dev.tachyonmcp.docs.kotlin.TypedEchoServerKt").use {
            val echo = rpcResult(DOCUMENTED_PORT, "tools/list").path("tools").get(0)
            echo.path("inputSchema").path("properties").path("message").path("type").asString() shouldBe "string"
            echo.path("outputSchema").path("properties").path("reply").path("type").asString() shouldBe "string"

            val result = rpcResult(DOCUMENTED_PORT, "tools/call", """{"name":"echo","arguments":{"message":"Hello, MCP!"}}""")
            result.path("structuredContent").path("reply").asString() shouldBe "Hello, MCP!"
            result.text() shouldBe """{"reply":"Hello, MCP!"}"""
        }
    }
}
