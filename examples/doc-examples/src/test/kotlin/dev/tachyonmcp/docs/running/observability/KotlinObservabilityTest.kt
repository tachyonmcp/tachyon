package dev.tachyonmcp.docs.running.observability

import dev.tachyonmcp.docs.ForkedMain
import dev.tachyonmcp.docs.kotlin.DOCUMENTED_PORT
import dev.tachyonmcp.docs.kotlin.rpcResult
import dev.tachyonmcp.docs.kotlin.text
import dev.tachyonmcp.kotlin.server.TachyonServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.api.common.AttributeKey
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration

class KotlinObservabilityTest {
    @Test
    fun `listener registered in the DSL records spans`() {
        ForkedMain.start("dev.tachyonmcp.docs.running.observability.KotlinListenerMainKt").use {
            rpcResult(DOCUMENTED_PORT, "server/discover").path("serverInfo").has("name") shouldBe
                true
        }
    }

    @Test
    fun `payload capture block adds arguments and results to the span`() {
        val captured = CapturingExporter()
        val openTelemetry = CapturingExporter.sdk(captured)
        TachyonServer(port = 0) {
            tool(
                name = "greet",
                inputSchema = """{"type":"object","properties":{"name":{"type":"string"}}}""",
            ) {
                text("Hello, ${arguments.stringValue("name")}!")
            }
            captureConfig(openTelemetry)
        }.use { server ->
            server
                .rpcResult(
                    "tools/call",
                    """{"name":"greet","arguments":{"name":"Ada"}}""",
                ).text() shouldBe
                "Hello, Ada!"

            await().atMost(Duration.ofSeconds(5)).until {
                captured.spans.any {
                    it.name ==
                        "tools/call greet"
                }
            }
            val attributes = captured.span("tools/call greet").attributes
            attributes.get(AttributeKey.stringKey("gen_ai.tool.call.arguments")) shouldContain "Ada"
            attributes.get(AttributeKey.stringKey("gen_ai.tool.call.result")) shouldContain
                "Hello, Ada!"
        }
    }

    @Test
    fun `custom listener registered in the DSL is called`() {
        ForkedMain
            .start(
                "dev.tachyonmcp.docs.running.observability.KotlinCustomListenerMainKt",
            ).use { forked ->
                rpcResult(DOCUMENTED_PORT, "server/discover")

                await().atMost(Duration.ofSeconds(10)).until {
                    forked.output().contains("OBS complete server/discover")
                }
                forked.output() shouldContain "OBS start server/discover"
            }
    }
}
