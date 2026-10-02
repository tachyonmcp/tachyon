package dev.tachyonmcp.docs.features.prompts

import dev.tachyonmcp.docs.kotlin.items
import dev.tachyonmcp.docs.kotlin.promptText
import dev.tachyonmcp.docs.kotlin.rpcResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KotlinPromptsTest {
    @Test
    fun `prompt DSL advertises the argument and renders the suspending handler`() {
        TachyonServer(port = 0) { reviewPrompt() }.use { server ->
            val prompt = server.rpcResult("prompts/list").path("prompts").get(0)
            prompt.path("name").asString() shouldBe "review-code"
            prompt.path("description").asString() shouldBe "Review code for a selected concern"
            val concern = prompt.path("arguments").items().single()
            concern.path("name").asString() shouldBe "concern"
            concern.path("required").asBoolean() shouldBe true

            server
                .rpcResult("prompts/get", """{"name":"review-code","arguments":{"concern":"security"}}""")
                .promptText() shouldBe "Review this code for security."
        }
    }
}
