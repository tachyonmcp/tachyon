package dev.tachyonmcp.docs.extensions.skills

import dev.tachyonmcp.docs.kotlin.DOCUMENTED_PORT
import dev.tachyonmcp.docs.kotlin.ForkedMain
import dev.tachyonmcp.docs.kotlin.items
import dev.tachyonmcp.docs.kotlin.rpcResult
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SkillsKotlinTest {
    @Test
    fun `main serves both registries and applies the cache settings`() {
        ForkedMain.start("dev.tachyonmcp.docs.extensions.skills.SkillsKotlinKt").use {
            val list =
                rpcResult(
                    DOCUMENTED_PORT,
                    "skills/list",
                    """{"_meta":{"io.modelcontextprotocol/skills":{}}}""",
                )

            list.path("skills").items().map { it.path("uri").asString() } shouldContainExactlyInAnyOrder
                listOf("skill://git-workflow/SKILL.md", "skill://code-review/SKILL.md")
            list.path("ttlMs").asLong() shouldBe 300_000L
            list.path("cacheScope").asString() shouldBe "public"
        }
    }
}
