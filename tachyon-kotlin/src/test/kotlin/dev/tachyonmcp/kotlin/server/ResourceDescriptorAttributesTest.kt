// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.features.resources.resourceDescriptor
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The flat `resource(...)` overload must accept the full optional attribute set of
 * [dev.tachyonmcp.api.server.features.resources.ResourceDescriptor.Builder].
 */
internal class ResourceDescriptorAttributesTest {
    @Test
    fun `every optional attribute reaches the descriptor`() {
        val icon = Icon { src = "https://example.com/resource.png" }

        buildServer {
            // when
            resource(
                name = "greeting",
                uri = "res://greeting",
                description = "desc",
                mimeType = "text/plain",
                title = "Title",
                size = 42,
                icons = listOf(icon),
                meta = mapOf("k" to "v"),
            ) { TextResourceContents { text = "hi" } }
        }.use { server ->
            // then
            server.resources().find("greeting") shouldBePresent {
                title() shouldBe "Title"
                description() shouldBe "desc"
                mimeType() shouldBe "text/plain"
                size() shouldBe 42L
                icons() shouldBe listOf(icon)
                meta() shouldBe mapOf("k" to "v")
            }
        }
    }

    @Test
    fun `extensionId is not a flat resource attribute, only settable via the descriptor scope`() {
        val descriptor = resourceDescriptor("greeting", "res://greeting") { extensionId = "ext" }

        descriptor.extensionId() shouldBe "ext"
    }
}
