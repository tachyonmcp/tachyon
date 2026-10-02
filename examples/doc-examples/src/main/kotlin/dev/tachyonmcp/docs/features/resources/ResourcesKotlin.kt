package dev.tachyonmcp.docs.features.resources

import dev.tachyonmcp.api.server.domain.TextResourceContents
import dev.tachyonmcp.kotlin.server.TachyonServer

internal fun loadUser(id: String): String = """{"id":"$id","displayName":"Ada"}"""

fun main() {
    // snips-start: resources_kotlin
    val server = TachyonServer(port = 8080) {
        resource(name = "config", uri = "app://config", mimeType = "application/json") {
            TextResourceContents.of(uri, """{"environment":"production"}""", "application/json")
        }

        resourceTemplate(name = "user-profile", uriTemplate = "app://users/{id}") {
            val id = params.getValue("id").scalarValue()
            TextResourceContents.of(uri, loadUser(id), "application/json")
        }
    }
    // snips-end: resources_kotlin
}
