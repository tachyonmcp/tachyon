package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.api.server.domain.Args
import dev.tachyonmcp.kotlin.server.domain.boolean
import dev.tachyonmcp.kotlin.server.domain.booleanOrNull
import dev.tachyonmcp.kotlin.server.domain.double
import dev.tachyonmcp.kotlin.server.domain.doubleOrNull
import dev.tachyonmcp.kotlin.server.domain.int
import dev.tachyonmcp.kotlin.server.domain.intOrNull
import dev.tachyonmcp.kotlin.server.domain.stringOrNull
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ArgsAccessorsTest {
    private val args = Args.of(mapOf("name" to "Ada", "count" to 3, "loud" to true, "ratio" to 0.5))

    @Test
    fun `required accessors return the value and throw when it is missing`() {
        args.stringValue("name") shouldBe "Ada"
        args.intValue("count") shouldBe 3
        args.boolValue("loud") shouldBe true
        args.doubleValue("ratio") shouldBe 0.5

        shouldThrowAny { args.stringValue("missing") }
        shouldThrowAny { args.intValue("missing") }
        shouldThrowAny { args.boolValue("missing") }
        shouldThrowAny { args.doubleValue("missing") }
    }

    @Test
    fun `OrNull accessors return null when the value is missing`() {
        args.stringOrNull("name") shouldBe "Ada"
        args.intOrNull("count") shouldBe 3
        args.booleanOrNull("loud") shouldBe true
        args.doubleOrNull("ratio") shouldBe 0.5

        args.stringOrNull("missing") shouldBe null
        args.intOrNull("missing") shouldBe null
        args.booleanOrNull("missing") shouldBe null
        args.doubleOrNull("missing") shouldBe null
    }

    @Test
    fun `default accessors fall back when the value is missing`() {
        args.stringOr("missing", "d") shouldBe "d"
        args.int("missing", 0) shouldBe 0
        args.boolean("missing", true) shouldBe true
        args.double("missing", 0.0) shouldBe 0.0

        args.stringOr("name", "d") shouldBe "Ada"
        args.int("count", 0) shouldBe 3
    }
}
