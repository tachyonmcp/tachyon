package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.server.domain.Role
import dev.tachyonmcp.kotlin.server.domain.Icon
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.util.Base64

class StructuredValuesTest {
    @Test
    fun `receiver factories fill every field`() {
        val values = structuredValues()

        values.annotations.audience() shouldBe listOf(Role.USER)
        values.annotations.priority() shouldBe 0.8
        values.icon.src() shouldBe "https://example.com/icon.svg"
        values.icon.mimeType() shouldBe "image/svg+xml"
        values.icon.sizes() shouldBe listOf("any")
        values.icon.theme() shouldBe "light"
    }

    @Test
    fun `required fields fail fast when the block finishes`() {
        shouldThrow<IllegalArgumentException> { Icon { mimeType = "image/png" } }.message shouldBe
            "Icon.src is required"
    }

    @Test
    fun `binary icon encodes src as a base64 data uri`() {
        val icon = binaryIcon()

        icon.src() shouldBe "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes)
        icon.mimeType() shouldBe "image/png"
        icon.sizes() shouldBe listOf("32x32")
    }

    @Test
    fun `binary data must be nonempty with a nonblank mime type and no src`() {
        shouldThrow<IllegalArgumentException> {
            Icon {
                data = ByteArray(0)
                mimeType = "image/png"
            }
        }.message shouldBe "data must not be empty"
        shouldThrow<IllegalArgumentException> {
            Icon {
                data = imageBytes
                mimeType = " "
            }
        }.message shouldBe "mimeType must not be blank"
        shouldThrow<IllegalArgumentException> { Icon { data = imageBytes } }.message shouldBe
            "Icon.mimeType is required for binary data"
        shouldThrow<IllegalArgumentException> {
            Icon {
                src = "https://example.com/icon.png"
                data = imageBytes
                mimeType = "image/png"
            }
        }.message shouldStartWith "Icon.src and Icon.data are mutually exclusive"
    }
}
