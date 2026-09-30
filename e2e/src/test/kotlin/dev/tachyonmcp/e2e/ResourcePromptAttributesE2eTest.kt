// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.e2e

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.domain.Annotations
import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.domain.PromptArgument
import dev.tachyonmcp.kotlin.server.features.prompts.PromptDescriptor
import dev.tachyonmcp.kotlin.server.features.resources.ResourceTemplateDescriptor
import dev.tachyonmcp.kotlin.server.features.resources.resourceDescriptor
import dev.tachyonmcp.testkit.Mcp20251125Client
import io.kotest.matchers.equals.shouldEqual
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Exercises the full flat-parameter attribute set of the Kotlin DSL's `resource`,
 * `resourceTemplate`, and `prompt` registration functions end-to-end. Every optional attribute
 * (`title`, `annotations`, `size`, `icons`, `meta`, `arguments`, `inputSchema`) is unused by every
 * other e2e/example call site in this repo, so this is the only place proving the full flat shape
 * actually works over the wire, not just at the unit level.
 *
 * `extensionId` is deliberately NOT one of the flat parameters — it's an extension-ownership
 * marker for extension implementations, set via the `*DescriptorScope`/`*Builder` DSL (or the raw
 * Java `*Descriptor.Builder`), never as an ordinary named argument. `ResourceMethodHandlers`/
 * `PromptMethodHandlers` filter both the list results and direct reads to only the extensions the
 * current session has negotiated (see `context.isExtensionEnabled`), so a resource/prompt carrying
 * an `extensionId` with no matching negotiated extension is — by design — completely absent from
 * the wire, not merely missing one field. That's asserted explicitly below (via the descriptor
 * overload) rather than treated as a wire-format detail like `inputSchema` (prompt-only, also not
 * on the wire, but not gated — just not part of the `Prompt` protocol model).
 */
internal class ResourcePromptAttributesE2eTest : AbstractStatelessMcpE2eTest<Mcp20251125Client>() {
    override fun createTestClient(): Mcp20251125Client = createTestClient(port)

    override fun createTestClient(port: Int): Mcp20251125Client = Mcp20251125Client(port)

    @Test
    fun `resource full attribute set round-trips over the wire`() {
        val icon =
            Icon {
                data = byteArrayOf(0, 1, -1)
                mimeType = "image/png"
                sizes = listOf("16x16")
                theme = "dark"
            }
        val annotations = Annotations { priority = 0.5 }

        TachyonServer(port = 0) {
            resource(
                name = "full-resource",
                uri = "test://full-resource",
                description = "Full resource",
                mimeType = "text/plain",
                title = "Full Resource Title",
                annotations = annotations,
                size = 123,
                icons = listOf(icon),
                meta = mapOf("owner" to "team-x"),
            ) {
                TextResourceContents { text = "hello" }
            }
            resource(
                descriptor =
                    resourceDescriptor("gated-resource", "test://gated-resource") {
                        extensionId = "com.example/resource-ext"
                    },
            ) {
                TextResourceContents { text = "gated" }
            }
        }.use { server ->
            server
                .resources()
                .find("gated-resource")
                .orElseThrow()
                .extensionId() shouldEqual "com.example/resource-ext"

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    """{"jsonrpc":"2.0","id":2,"method":"resources/list","params":{}}""",
                )

            response.statusCode() shouldEqual 200
            val body = response.body()
            body shouldContain """"uri":"test://full-resource""""
            body shouldContain """"description":"Full resource""""
            body shouldContain """"mimeType":"text/plain""""
            body shouldContain """"title":"Full Resource Title""""
            body shouldContain """"priority":0.5"""
            body shouldContain """"size":123"""
            body shouldContain
                """"icons":[{"src":"data:image/png;base64,AAH/","mimeType":"image/png","sizes":["16x16"],"theme":"dark"}]"""
            body shouldContain """"owner":"team-x""""
            // extensionId gates visibility: no negotiated extension for "com.example/resource-ext"
            // means this session never sees the resource, not even without the field.
            body shouldNotContain """"uri":"test://gated-resource""""
        }
    }

    @Test
    fun `resourceTemplate full attribute set round-trips over the wire`() {
        val icon = Icon { src = "https://example.com/template-icon.png" }
        val annotations = Annotations { priority = 0.7 }

        TachyonServer(port = 0) {
            resourceTemplate(
                name = "full-template",
                uriTemplate = "test://full-template/{id}",
                description = "Full template",
                mimeType = "application/json",
                title = "Full Template Title",
                annotations = annotations,
                icons = listOf(icon),
                meta = mapOf("owner" to "team-y"),
            ) {
                TextResourceContents { text = """{"id":"${param("id")}"}""" }
            }
            resourceTemplate(
                descriptor =
                    ResourceTemplateDescriptor {
                        name = "gated-template"
                        uriTemplate = "test://gated-template/{id}"
                        extensionId = "com.example/template-ext"
                    },
            ) {
                TextResourceContents { text = """{"id":"${param("id")}"}""" }
            }
        }.use { server ->
            server
                .resources()
                .findTemplate("gated-template")
                .orElseThrow()
                .extensionId() shouldEqual "com.example/template-ext"

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    """{"jsonrpc":"2.0","id":2,"method":"resources/templates/list","params":{}}""",
                )

            response.statusCode() shouldEqual 200
            val body = response.body()
            body shouldContain """"uriTemplate":"test://full-template/{id}""""
            body shouldContain """"description":"Full template""""
            body shouldContain """"mimeType":"application/json""""
            body shouldContain """"title":"Full Template Title""""
            body shouldContain """"priority":0.7"""
            body shouldContain """"src":"https://example.com/template-icon.png""""
            body shouldContain """"owner":"team-y""""
            // extensionId gates visibility: no negotiated extension for "com.example/template-ext"
            // means this session never sees the template, not even without the field.
            body shouldNotContain """"uriTemplate":"test://gated-template/{id}""""
        }
    }

    @Test
    fun `prompt full attribute set round-trips over the wire`() {
        val icon = Icon { src = "https://example.com/prompt-icon.png" }
        val argument =
            PromptArgument {
                name = "topic"
                required = true
            }

        TachyonServer(port = 0) {
            prompt(
                name = "full-prompt",
                description = "Full prompt",
                title = "Full Prompt Title",
                arguments = listOf(argument),
                inputSchema = JsonSchema.objectSchema(),
                icons = listOf(icon),
                meta = mapOf("owner" to "team-z"),
            ) {
                content { text("Discuss the topic") }
            }
            prompt(
                descriptor =
                    PromptDescriptor {
                        name = "gated-prompt"
                        extensionId = "com.example/prompt-ext"
                    },
            ) {
                content { text("Gated") }
            }
        }.use { server ->
            with(server.prompts().find("full-prompt").orElseThrow()) {
                inputSchema()?.json() shouldEqual JsonSchema.objectSchema().json()
            }
            server
                .prompts()
                .find("gated-prompt")
                .orElseThrow()
                .extensionId() shouldEqual "com.example/prompt-ext"

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    """{"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{}}""",
                )

            response.statusCode() shouldEqual 200
            val body = response.body()
            body shouldContain """"name":"full-prompt""""
            body shouldContain """"description":"Full prompt""""
            body shouldContain """"title":"Full Prompt Title""""
            body shouldContain """"name":"topic""""
            body shouldContain """"required":true"""
            body shouldContain """"src":"https://example.com/prompt-icon.png""""
            body shouldContain """"owner":"team-z""""
            // extensionId gates visibility, same as resources.
            body shouldNotContain """"name":"gated-prompt""""
        }
    }
}
