package dev.tachyonmcp.docs.running.configuration

import dev.tachyonmcp.docs.RawHttp
import dev.tachyonmcp.docs.kotlin.DOCUMENTED_PORT
import dev.tachyonmcp.docs.kotlin.ForkedMain
import dev.tachyonmcp.docs.kotlin.rpcResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse
import dev.tachyonmcp.testkit.McpTestClients
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KotlinConfigurationTest {
    @Test
    fun `basic main applies identity and sessions`() {
        ForkedMain.start("dev.tachyonmcp.docs.running.configuration.KotlinBasicMainKt").use {
            val info = rpcResult(DOCUMENTED_PORT, "server/discover").path("serverInfo")
            info.path("name").asString() shouldBe "my-server"
            info.path("version").asString() shouldBe "1.0"
            McpTestClients.forVersion(DOCUMENTED_PORT, "2025-11-25").use { client ->
                (client.initialize() != null) shouldBe true
            }
        }
    }

    @Test
    fun `allowedHosts plus-assign admits the extra authority`() {
        TachyonServer(port = 0) { extraHost() }.use { server ->
            RawHttp.postStatus(server.port(), "localhost:${server.port()}") shouldBe 200
            RawHttp.postStatus(server.port(), "host.docker.internal:8096") shouldBe 200
            RawHttp.postStatus(server.port(), "evil.example.com") shouldBe 403
        }
    }

    @Test
    fun `explicit io engine without its transport fails at startup`() {
        shouldThrow<UnsupportedOperationException> { TachyonServer(port = 0) { epoll() } }
    }

    @Test
    fun `stateless after a session option is a build-time contradiction`() {
        shouldThrow<IllegalStateException> { buildServer { sessionAlternatives() } }
    }

    @Test
    fun `observability main starts and serves`() {
        ForkedMain.start("dev.tachyonmcp.docs.running.configuration.KotlinObservabilityMainKt").use {
            rpcResult(DOCUMENTED_PORT, "server/discover").path("serverInfo").has("name") shouldBe true
        }
    }

    @Test
    fun `capabilities main advertises the configured features`() {
        ForkedMain.start("dev.tachyonmcp.docs.running.configuration.KotlinCapabilitiesMainKt").use {
            val capabilities = rpcResult(DOCUMENTED_PORT, "server/discover").path("capabilities")
            capabilities.path("tools").path("listChanged").asBoolean() shouldBe true
            capabilities.has("resources") shouldBe true
            capabilities.has("completions") shouldBe true
            capabilities.has("logging") shouldBe true
            McpTestClients.forVersion(DOCUMENTED_PORT, "2025-11-25").use { client ->
                val initialize =
                    client.post(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                        |"capabilities":{},"clientInfo":{"name":"t","version":"1"}}}
                        """.trimMargin(),
                    )
                assertThatResponse(initialize)
                    .hasStatus(200)
                    .isSuccess()
                    .result()
                    .path("capabilities")
                    .path("resources")
                    .path("subscribe")
                    .asBoolean() shouldBe true
            }
        }
    }
}
