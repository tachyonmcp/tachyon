package dev.tachyonmcp.docs.kotlin.migratefromkotlinsdk

// snips-start: migrate_logo_icon
import dev.tachyonmcp.kotlin.server.domain.Icon
import java.util.Base64

val logoIcon =
    object {}.javaClass.getResourceAsStream("/logo-32x32.png")!!.use { s ->
        Icon {
            src = "data:image/png;base64,${Base64.getEncoder().encodeToString(s.readAllBytes())}"
            sizes = listOf("32x32")
            mimeType = "image/png"
        }
    }
// snips-end: migrate_logo_icon
