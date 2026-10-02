package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.kotlin.server.TachyonServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HandlerExamplesTest {
    @Test
    fun `resource handler publishes every attribute and reads through a suspend call`() {
        TachyonServer(port = 0) { resourceAndPromptHandlers() }.use { server ->
            val resource = server.rpcResult("resources/list").path("resources").get(0)
            resource.path("name").asString() shouldBe "config"
            resource.path("uri").asString() shouldBe "demo://config"
            resource.path("title").asString() shouldBe "Configuration"
            resource.path("description").asString() shouldBe "Application configuration"
            resource.path("mimeType").asString() shouldBe "application/json"
            resource.path("size").asInt() shouldBe 1024
            resource.path("annotations").path("priority").asDouble() shouldBe 0.8
            resource.path("icons").get(0).path("src").asString() shouldBe "https://example.com/config.svg"
            resource.path("_meta").path("owner").asString() shouldBe "team-x"

            val contents = server.rpcResult("resources/read", """{"uri":"demo://config"}""").path("contents").get(0)
            contents.path("uri").asString() shouldBe "demo://config"
            contents.path("mimeType").asString() shouldBe "application/json"
            contents.path("text").asString() shouldBe """{"env":"prod"}"""
        }
    }

    @Test
    fun `prompt handler greets by argument and falls back to world`() {
        TachyonServer(port = 0) { resourceAndPromptHandlers() }.use { server ->
            server.rpcResult("prompts/get", """{"name":"greet","arguments":{"name":"Ada"}}""").promptText() shouldBe
                "Hello, Ada"
            server.rpcResult("prompts/get", """{"name":"greet"}""").promptText() shouldBe "Hello, world"
        }
    }

    @Test
    fun `prompt accepts the full descriptor attribute set as named params`() {
        TachyonServer(port = 0) { promptDescriptorAttributes() }.use { server ->
            val prompt = server.rpcResult("prompts/list").path("prompts").get(0)
            prompt.path("name").asString() shouldBe "rewrite"
            prompt.path("title").asString() shouldBe "Rewrite Tool"
            prompt.path("description").asString() shouldBe "Rewrites text in a style"
            prompt.path("arguments").get(0).path("name").asString() shouldBe "style"
            prompt.path("arguments").get(0).path("required").asBoolean() shouldBe false
            prompt.path("icons").get(0).path("src").asString() shouldBe "https://example.com/rewrite.svg"
            prompt.path("_meta").path("owner").asString() shouldBe "team-x"

            server.rpcResult("prompts/get", """{"name":"rewrite","arguments":{"style":"pirate"}}""")
                .promptText() shouldBe "Rewrite this in pirate style"
            server.rpcResult("prompts/get", """{"name":"rewrite"}""").promptText() shouldBe
                "Rewrite this in a neutral style"
        }
    }

    @Test
    fun `prebuilt resource descriptor is shared metadata`() {
        TachyonServer(port = 0) { resourceDescriptor() }.use { server ->
            val resource = server.rpcResult("resources/list").path("resources").get(0)
            resource.path("title").asString() shouldBe "Configuration"
            resource.path("mimeType").asString() shouldBe "application/json"

            val contents = server.rpcResult("resources/read", """{"uri":"demo://config"}""").path("contents").get(0)
            contents.path("text").asString() shouldBe """{"env":"prod"}"""
            contents.path("mimeType").asString() shouldBe "application/json"
        }
    }

    @Test
    fun `resource template matches variables and defaults uri and mime type`() {
        TachyonServer(port = 0) { resourceTemplate() }.use { server ->
            val template = server.rpcResult("resources/templates/list").path("resourceTemplates").get(0)
            template.path("name").asString() shouldBe "user-profile"
            template.path("uriTemplate").asString() shouldBe "user://{userId}/profile"
            template.path("title").asString() shouldBe "User profile"
            template.path("mimeType").asString() shouldBe "application/json"

            val contents = server.rpcResult("resources/read", """{"uri":"user://42/profile"}""").path("contents").get(0)
            contents.path("uri").asString() shouldBe "user://42/profile"
            contents.path("mimeType").asString() shouldBe "application/json"
            contents.path("text").asString() shouldBe """{"id":"42"}"""
        }
    }

    @Test
    fun `prebuilt template descriptor reads through a suspend call`() {
        TachyonServer(port = 0) { resourceTemplateDescriptor() }.use { server ->
            val contents = server.rpcResult("resources/read", """{"uri":"docs://readme"}""").path("contents").get(0)
            contents.path("uri").asString() shouldBe "docs://readme"
            contents.path("mimeType").asString() shouldBe "text/markdown"
            contents.path("text").asString() shouldBe "# readme"
        }
    }
}
