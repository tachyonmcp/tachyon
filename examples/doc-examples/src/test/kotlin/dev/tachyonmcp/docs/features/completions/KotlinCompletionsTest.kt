package dev.tachyonmcp.docs.features.completions

import dev.tachyonmcp.docs.kotlin.items
import dev.tachyonmcp.docs.kotlin.rpcResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class KotlinCompletionsTest {
    private fun JsonNode.completionValues(): List<String> = path("completion").path("values").items().map { it.asString() }

    @Test
    fun `prompt and resource completion handlers answer completion requests`() {
        TachyonServer(port = 0) { completionHandlers(KotlinCityService()) }.use { server ->
            server
                .rpcResult(
                    "completion/complete",
                    """{"ref":{"type":"ref/prompt","name":"review-code"},"argument":{"name":"concern","value":"se"}}""",
                ).completionValues() shouldContainExactly listOf("security")

            server
                .rpcResult(
                    "completion/complete",
                    """{"ref":{"type":"ref/prompt","name":"review-code"},"argument":{"name":"other","value":"se"}}""",
                ).completionValues()
                .shouldBeEmpty()

            server
                .rpcResult(
                    "completion/complete",
                    """{"ref":{"type":"ref/resource","uri":"weather://current/{city}"},"argument":{"name":"city","value":"p"}}""",
                ).completionValues() shouldContainExactly listOf("Paris", "Prague")
        }
    }
}
