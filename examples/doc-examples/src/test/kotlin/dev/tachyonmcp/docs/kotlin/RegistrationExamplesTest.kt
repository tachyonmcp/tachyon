package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.domain.TextResourceContents
import dev.tachyonmcp.kotlin.server.features.completions.CompletionResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.concurrent.atomic.AtomicInteger

class RegistrationExamplesTest {
    private fun complete(
        server: dev.tachyonmcp.core.server.TachyonServer,
        ref: String,
        argumentName: String,
        value: String,
    ): JsonNode =
        server
            .rpcResult(
                "completion/complete",
                """{"ref":$ref,"argument":{"name":"$argumentName","value":"$value"}}""",
            ).path("completion")

    private fun JsonNode.completionValues(): List<String> = path("values").items().map { it.asString() }

    private val promptRef = """{"type":"ref/prompt","name":"rewrite-forecast"}"""
    private val resourceRef = """{"type":"ref/resource","uri":"myapp://users/{userId}/profile"}"""

    @Test
    fun `completions answer prompt and resource refs and cap at 100 values`() {
        TachyonServer(port = 0) {
            completions()
            promptCompletion("big") { CompletionResult { values = List(150) { "v$it" } } }
        }.use { server ->
            complete(server, promptRef, "style", "p").completionValues() shouldContainExactly listOf("plain", "pirate")

            val users = complete(server, resourceRef, "userId", "a")
            users.completionValues() shouldContainExactly listOf("alice")
            users.path("hasMore").asBoolean() shouldBe false

            complete(server, """{"type":"ref/prompt","name":"unknown"}""", "x", "").completionValues().shouldBeEmpty()

            val capped = complete(server, """{"type":"ref/prompt","name":"big"}""", "x", "")
            capped.path("values").size() shouldBe 100
            capped.path("hasMore").asBoolean() shouldBe true
        }
    }

    @Test
    fun `register twins work before and after start`() {
        postBuildRegistration().use { server ->
            server.start()
            server.port() shouldBeGreaterThan 0

            server.rpcResult("tools/call", """{"name":"echo","arguments":{"msg":"hello"}}""").text() shouldBe "hello"
            server.rpcResult("resources/read", """{"uri":"myapp://config"}""")
                .path("contents").get(0).path("text").asString() shouldBe """{"mode":"demo"}"""
            server.rpcResult("resources/read", """{"uri":"myapp://users/7/profile"}""")
                .path("contents").get(0).path("text").asString() shouldBe """{"userId":"7"}"""
            server.rpcResult("prompts/get", """{"name":"rewrite-forecast"}""").promptText() shouldBe
                "Rewrite this forecast."
            complete(server, promptRef, "style", "").completionValues() shouldContainExactly
                listOf("plain", "concise", "pirate")
            complete(server, resourceRef, "userId", "").completionValues() shouldContainExactly listOf("alice", "bob")

            server.registerTool(name = "late") { text("registered after start") }
            server.rpcResult("tools/call", """{"name":"late","arguments":{}}""").text() shouldBe
                "registered after start"
        }
    }

    @Test
    fun `duplicate registration behaves as the table says`() {
        buildServer { network { port = 0 } }.use { server ->
            server.registerTool("t") { text("first") }
            server.registerTool("t") { text("second") }

            server.registerResource(name = "r", uri = "x://r") { TextResourceContents { text = "one" } }
            server.registerResource(name = "r", uri = "x://r") { TextResourceContents { text = "two" } }
            shouldThrow<IllegalArgumentException> {
                server.registerResource(name = "other", uri = "x://r") { TextResourceContents { text = "three" } }
            }

            server.registerResourceTemplate(name = "tpl", uriTemplate = "x://{id}") {
                TextResourceContents { text = "template" }
            }
            shouldThrow<IllegalArgumentException> {
                server.registerResourceTemplate(name = "tpl", uriTemplate = "x://{id}") {
                    TextResourceContents { text = "again" }
                }
            }

            server.start()
            server.rpcResult("tools/call", """{"name":"t","arguments":{}}""").text() shouldBe "second"
            server.rpcResult("resources/read", """{"uri":"x://r"}""")
                .path("contents").get(0).path("text").asString() shouldBe "two"
        }
    }

    @Test
    fun `registering for a disabled capability is a no-op that returns the server`() {
        buildServer { capabilities { tools { mode = Mode.OFF } } }.use { server ->
            server.registerTool("t") { text("x") } shouldBeSameInstanceAs server
        }
    }

    @Test
    fun `pipeline customizer sees inbound requests only ahead of the MCP handlers`() {
        val before = MetricsHandler.reads.get()
        TachyonServer(port = 0) { pipelineCustomization() }.use { server -> server.post("ping") }
        MetricsHandler.reads.get() shouldBeGreaterThan before

        val behind = AtomicInteger()
        TachyonServer(port = 0) {
            pipelineCustomizer {
                addLast(
                    "behind",
                    object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(
                            ctx: ChannelHandlerContext,
                            msg: Any,
                        ) {
                            behind.incrementAndGet()
                            ctx.fireChannelRead(msg)
                        }
                    },
                )
            }
        }.use { server -> server.post("ping") }
        behind.get() shouldBe 0
    }
}
