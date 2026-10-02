package dev.tachyonmcp.docs.kotlin.migratefromkotlinsdk

import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

internal fun configuredServer(
    mcpPort: Int,
    appVersion: String,
    yourJson: Json,
): TachyonServer {
    // snips-start: migrate_server
    val server = TachyonServer(port = mcpPort) {
        info {
            name = "example-server"
            title = "Example MCP"
            version = appVersion
            websiteUrl = "https://example.com/docs"
            icons.add(logoIcon)
        }
        capabilities {
            tools { listChanged = true }
            resources {
                subscribe = false
                listChanged = true
            }
            logging = true
        }
        json { serde = KxSerializationSerde(json = yourJson) }   // reuse your kotlinx Json config
        network {
            host = "127.0.0.1"
            allowedOrigins.add("https://your-client.example.com")
        }
        session { sessionTtl = 10.minutes }
    }
    // snips-end: migrate_server
    return server
}

internal fun lifecycle(server: TachyonServer) {
    // snips-start: migrate_lifecycle
    server.tools()    // register tools through feature registries or Kotlin extensions
    server.port()
    server.close()    // wire to your app's stop hook
    // snips-end: migrate_lifecycle
}
