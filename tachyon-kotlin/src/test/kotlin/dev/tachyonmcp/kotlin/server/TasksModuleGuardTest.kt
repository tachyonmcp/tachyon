// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.kotlin.server.config.requireTasksModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * The tasks module is optional; E2E always has it, so the missing-jar path is covered here and
 * `TasksExtension` is simulated as absent.
 */
internal class TasksModuleGuardTest {
    @Test
    fun `missing tasks module becomes IllegalStateException naming the dependency`() {
        val missing = NoClassDefFoundError("dev/tachyonmcp/extensions/tasks/TasksExtension")

        val error = shouldThrow<IllegalStateException> { requireTasksModule { throw missing } }

        error.message shouldBe
            "Kotlin tasks API needs dev.tachyonmcp:tachyon-extensions-tasks on the classpath; " +
            "tachyon-kotlin declares it optional, so add it to your build"
        error.cause shouldBeSameInstanceAs missing
    }

    @Test
    fun `unrelated missing class propagates unchanged`() {
        val unrelated = NoClassDefFoundError("com/example/Other")

        val error = shouldThrow<NoClassDefFoundError> { requireTasksModule { throw unrelated } }

        error shouldBeSameInstanceAs unrelated
    }

    @Test
    fun `present tasks module returns the block result`() {
        requireTasksModule { "ok" } shouldBe "ok"
    }
}
