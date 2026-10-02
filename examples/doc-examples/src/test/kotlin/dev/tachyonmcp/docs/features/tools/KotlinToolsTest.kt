package dev.tachyonmcp.docs.features.tools

import dev.tachyonmcp.docs.kotlin.rpcResult
import dev.tachyonmcp.docs.kotlin.text
import dev.tachyonmcp.kotlin.server.TachyonServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KotlinToolsTest {
    @Test
    fun `reverse tool reads the argument and returns text`() {
        TachyonServer(port = 0) { reverseTool() }.use { server ->
            server
                .rpcResult("tools/call", """{"name":"reverse","arguments":{"message":"stressed"}}""")
                .text() shouldBe "desserts"
        }
    }

    @Test
    fun `typed tool decodes the arguments and returns a structured reply`() {
        TachyonServer(port = 0) { typedEchoTool() }.use { server ->
            val result = server.rpcResult("tools/call", """{"name":"echo","arguments":{"message":"hi"}}""")

            result.path("structuredContent").path("echo").asString() shouldBe "hi"
            result.text() shouldBe """{"echo":"hi"}"""
        }
    }
}
