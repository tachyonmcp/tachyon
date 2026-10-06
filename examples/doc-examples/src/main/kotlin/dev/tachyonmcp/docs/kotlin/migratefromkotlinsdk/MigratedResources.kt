package dev.tachyonmcp.docs.kotlin.migratefromkotlinsdk

import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.features.resources.ResourceTemplateDescriptor

internal fun read(uri: String): String? =
    when (uri) {
        "example://docs/readme" -> "# README"
        "example://docs/guide" -> "# Guide"
        else -> null
    }

internal fun resourceServer(mcpPort: Int): TachyonServer {
    // snips-start: migrate_resources
    val server =
        TachyonServer(port = mcpPort) {
            // Concrete resource: appears in resources/list.
            resource(
                name = "readme",
                uri = "example://docs/readme",
                description = "Project documentation",
                mimeType = "text/markdown",
                title = "README",
            ) {
                TextResourceContents {
                    text = read(this@resource.uri) ?: error("not found")
                }
            }

            // URI template: appears in resources/templates/list.
            resourceTemplate(
                name = "docs",
                uriTemplate = "example://docs/{path}",
                description = "Docs",
                mimeType = "text/markdown",
            ) {
                TextResourceContents {
                    text = read(this@resourceTemplate.uri) ?: error("not found")
                }
            }
        }
    // snips-end: migrate_resources
    return server
}

internal fun descriptorServer(mcpPort: Int): TachyonServer {
    // snips-start: migrate_resource_descriptor
    val docs =
        ResourceTemplateDescriptor {
            name = "docs"
            uriTemplate = "example://docs/{path}"
            description = "Docs"
            mimeType = "text/markdown"
        }

    val server =
        TachyonServer(port = mcpPort) {
            resourceTemplate(docs) {
                TextResourceContents {
                    text = read(this@resourceTemplate.uri) ?: error("not found")
                }
            }
        }
    // snips-end: migrate_resource_descriptor
    return server
}
