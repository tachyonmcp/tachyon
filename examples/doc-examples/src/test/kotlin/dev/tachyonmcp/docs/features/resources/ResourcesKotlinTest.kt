package dev.tachyonmcp.docs.features.resources

import dev.tachyonmcp.docs.kotlin.DOCUMENTED_PORT
import dev.tachyonmcp.docs.kotlin.ForkedMain
import dev.tachyonmcp.docs.kotlin.rpcResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResourcesKotlinTest {
    @Test
    fun `main serves the fixed resource and the template`() {
        ForkedMain.start("dev.tachyonmcp.docs.features.resources.ResourcesKotlinKt").use {
            val config =
                rpcResult(DOCUMENTED_PORT, "resources/read", """{"uri":"app://config"}""")
                    .path("contents")
                    .get(0)
            config.path("text").asString() shouldBe """{"environment":"production"}"""
            config.path("mimeType").asString() shouldBe "application/json"

            val user =
                rpcResult(DOCUMENTED_PORT, "resources/read", """{"uri":"app://users/42"}""")
                    .path("contents")
                    .get(0)
            user.path("text").asString() shouldBe loadUser("42")
            user.path("uri").asString() shouldBe "app://users/42"
        }
    }
}
