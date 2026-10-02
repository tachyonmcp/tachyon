package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.kotlin.server.buildServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ServerEntryPointsTest {
    @Test
    fun `port zero listens on an ephemeral port and answers ping`() {
        ephemeralPortServer().use { server ->
            server.port() shouldBeGreaterThan 0
            server.host() shouldBe "127.0.0.1"
            server.rpcResult("tools/call", """{"name":"ping","arguments":{}}""").text() shouldBe "pong"
        }
    }

    @Test
    fun `buildServer does not listen until started and needs a port first`() {
        buildServer { }.use { server ->
            shouldThrow<IllegalStateException> { server.port() }
            shouldThrow<IllegalStateException> { server.start() }.message shouldBe
                "Port must be set before start()"
        }

        buildServer { network { port = 0 } }.use { server ->
            shouldThrow<IllegalStateException> { server.port() }
            server.start()
            server.port() shouldBeGreaterThan 0
        }
    }
}
