// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot
import dev.tachyonmcp.api.server.features.tasks.TaskState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

internal class TaskSnapshotFactoriesTest {
    @Test
    fun `TaskSnapshot keeps the owner key it is given and the one it copies`() {
        val working = TaskSnapshot.working("wf-o", Instant.parse("2026-09-24T07:00:00Z"), 1)
        val owned = TaskSnapshot(from = working) { ownerKey = "issuer|alice" }

        owned.ownerKey() shouldBe "issuer|alice"
        TaskSnapshot(from = owned) { status = TaskState.CANCELLED }.ownerKey() shouldBe
            "issuer|alice"
        TaskSnapshot(from = owned) { ownerKey = null }.ownerKey() shouldBe null
    }
}
