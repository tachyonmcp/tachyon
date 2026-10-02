package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder
import dev.tachyonmcp.kotlin.server.domain.Annotations
import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.domain.PromptArgument
import dev.tachyonmcp.kotlin.server.domain.stringOrNull
import dev.tachyonmcp.kotlin.server.features.resources.ResourceDescriptor
import dev.tachyonmcp.kotlin.server.features.resources.ResourceTemplateDescriptor

internal suspend fun fetchConfig(): String = """{"env":"prod"}"""

internal suspend fun loadDocument(path: String): String = "# $path"

internal val schema: JsonSchema =
    JsonSchema.unchecked("""{"type":"object","properties":{"style":{"type":"string"}}}""")

internal fun TachyonServerBuilder.resourceAndPromptHandlers() {
    // snips-start: kotlin_resource_handler
    resource(
        name = "config",
        uri = "demo://config",
        description = "Application configuration",
        mimeType = "application/json",
        title = "Configuration",
        annotations = Annotations { priority = 0.8 },
        size = 1024,
        icons = listOf(Icon { src = "https://example.com/config.svg" }),
        meta = mapOf("owner" to "team-x"),
    ) {
        // this: ResourceScope — ctx, uri, params, uriTemplate
        val config = fetchConfig() // suspend call
        TextResourceContents { text = config }
    }

    prompt(name = "greet", description = "Greeting prompt") {
        // this: PromptScope — ctx, request, arguments
        listOf(PromptMessage.user("Hello, ${arguments.stringOr("name", "world")}"))
    }
    // snips-end: kotlin_resource_handler
}

internal fun TachyonServerBuilder.promptDescriptorAttributes() {
    // snips-start: kotlin_prompt_attributes
    prompt(
        name = "rewrite",
        description = "Rewrites text in a style",
        title = "Rewrite Tool",
        arguments =
            listOf(
                PromptArgument {
                    name = "style"
                    required = false
                },
            ),
        inputSchema = schema,
        icons = listOf(Icon { src = "https://example.com/rewrite.svg" }),
        meta = mapOf("owner" to "team-x"),
    ) {
        val style = arguments.stringOrNull("style") ?: "a neutral"
        listOf(PromptMessage.user("Rewrite this in $style style"))
    }
    // snips-end: kotlin_prompt_attributes
}

internal fun TachyonServerBuilder.resourceDescriptor() {
    // snips-start: kotlin_resource_descriptor
    val descriptor =
        ResourceDescriptor {
            name = "config"
            uri = "demo://config"
            description = "Application configuration"
            mimeType = "application/json"
            title = "Configuration"
        }

    resource(descriptor) {
        val config = fetchConfig() // suspend call
        TextResourceContents { text = config }
    }
    // snips-end: kotlin_resource_descriptor
}

internal fun TachyonServerBuilder.resourceTemplate() {
    // snips-start: kotlin_resource_template
    resourceTemplate(
        name = "user-profile",
        uriTemplate = "user://{userId}/profile",
        description = "User profile template",
        mimeType = "application/json",
        title = "User profile",
        annotations = Annotations { priority = 0.8 },
        icons =
            listOf(
                Icon {
                    src = "https://example.com/user.svg"
                    mimeType = "image/svg+xml"
                },
            ),
    ) {
        TextResourceContents {
            text = """{"id":"${param("userId")}"}"""
        }
    }
    // snips-end: kotlin_resource_template
}

internal fun TachyonServerBuilder.resourceTemplateDescriptor() {
    // snips-start: kotlin_resource_template_descriptor
    val descriptor =
        ResourceTemplateDescriptor {
            name = "document"
            uriTemplate = "docs://{path}"
            description = "Documentation"
            mimeType = "text/markdown"
        }

    resourceTemplate(descriptor) {
        val document = loadDocument(param("path")) // suspend call
        TextResourceContents { text = document }
    }
    // snips-end: kotlin_resource_template_descriptor
}
