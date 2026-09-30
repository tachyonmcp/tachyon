// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class StructuredFactoryBuildersTest {
    @Test
    fun `Annotations block omits audience defaults to empty list`() {
        val annotations = Annotations { priority = 0.5 }

        annotations.audience() shouldBe emptyList()
    }

    @Test
    fun `Icon block omits sizes defaults to empty list`() {
        val icon = Icon { src = "https://example.test/icon.png" }

        icon.src() shouldBe "https://example.test/icon.png"
        icon.sizes() shouldBe emptyList()
    }

    @Test
    fun `Icon string factory preserves a data URI and metadata`() {
        val icon = Icon("data:image/png;base64,AAH/", "image/png", listOf("16x16"), "dark")

        icon.src() shouldBe "data:image/png;base64,AAH/"
        icon.mimeType() shouldBe "image/png"
        icon.sizes() shouldBe listOf("16x16")
        icon.theme() shouldBe "dark"
    }

    @Test
    fun `binary Icon block rejects ambiguous or incomplete input`() {
        shouldThrow<IllegalArgumentException> {
            Icon {
                src = "https://example.test/icon.png"
                data = byteArrayOf(1)
                mimeType =
                    "image/png"
            }
        }
        shouldThrow<IllegalArgumentException> { Icon { data = byteArrayOf(1) } }
        shouldThrow<IllegalArgumentException> {
            Icon {
                data = byteArrayOf()
                mimeType = "image/png"
            }
        }
        shouldThrow<IllegalArgumentException> {
            Icon {
                data = byteArrayOf(1)
                mimeType = " "
            }
        }
    }
}
