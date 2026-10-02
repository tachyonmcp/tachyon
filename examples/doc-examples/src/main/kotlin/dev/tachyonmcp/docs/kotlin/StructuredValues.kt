package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.server.domain.Role
import dev.tachyonmcp.kotlin.server.domain.Annotations
import dev.tachyonmcp.kotlin.server.domain.Icon

internal data class StructuredValues(
    val annotations: dev.tachyonmcp.api.server.domain.Annotations,
    val icon: dev.tachyonmcp.api.server.domain.Icon,
)

internal fun structuredValues(): StructuredValues {
    // snips-start: kotlin_structured_factories
    val annotations =
        Annotations {
            audience = listOf(Role.USER)
            priority = 0.8
        }

    val icon =
        Icon {
            src = "https://example.com/icon.svg"
            mimeType = "image/svg+xml"
            sizes = listOf("any")
            theme = "light"
        }
    // snips-end: kotlin_structured_factories
    return StructuredValues(annotations, icon)
}

internal val imageBytes: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal fun binaryIcon(): dev.tachyonmcp.api.server.domain.Icon {
    // snips-start: kotlin_binary_icon
    val icon =
        Icon {
            data = imageBytes
            mimeType = "image/png"
            sizes = listOf("32x32")
        }
    // snips-end: kotlin_binary_icon
    return icon
}
