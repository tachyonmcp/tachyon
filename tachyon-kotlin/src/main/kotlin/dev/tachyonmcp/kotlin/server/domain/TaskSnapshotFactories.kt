// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.server.domain.InputRequestBundle
import dev.tachyonmcp.api.server.domain.TaskResult
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot
import dev.tachyonmcp.api.server.features.tasks.TaskState
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinDuration
import kotlin.time.toKotlinInstant

/**
 * Builds a [TaskSnapshot] with a receiver DSL. With [from], builds its next revision: fields start from
 * [from] and the revision is `from.revision() + 1` ([TaskSnapshot.Builder.next]), so
 * [dev.tachyonmcp.api.server.features.tasks.Tasks.publish] applies it:
 *
 * ```kotlin
 * tasks.publish(
 *     TaskSnapshot(from = previous) {
 *         status = TaskState.COMPLETED
 *         result = TaskResult.completed(ToolResult.text("Charged"))
 *         lastUpdatedAt = Clock.System.now()
 *     },
 * )
 * ```
 *
 * @throws IllegalArgumentException if a required field is missing or the snapshot is invalid
 */
@ExperimentalApi
@OptIn(ExperimentalContracts::class)
public fun TaskSnapshot(
    from: TaskSnapshot? = null,
    block: TaskSnapshotBuilder.() -> Unit,
): TaskSnapshot {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return TaskSnapshotBuilder(from).apply(block).build()
}

/**
 * Builds a [TaskSnapshot]; required: [taskId], [status], [createdAt], [lastUpdatedAt], and [revision]
 * unless built from a previous snapshot.
 */
@TachyonDsl
@ExperimentalApi
@OptIn(ExperimentalTime::class)
public class TaskSnapshotBuilder
    internal constructor(
        private val from: TaskSnapshot?,
    ) {
        /** Stable task identifier. */
        public var taskId: String? = from?.taskId()

        /** Current task state. */
        public var status: TaskState? = from?.status()

        /** Optional human-readable state description. */
        public var statusMessage: String? = from?.statusMessage()

        /** Task creation timestamp. */
        public var createdAt: Instant? = from?.createdAt()?.toKotlinInstant()

        /** Latest state observation timestamp. */
        public var lastUpdatedAt: Instant? = from?.lastUpdatedAt()?.toKotlinInstant()

        /** Optional task lifetime measured from [createdAt]. */
        public var ttl: Duration? = from?.ttl()?.toKotlinDuration()

        /** Optional suggested client polling interval. */
        public var pollInterval: Duration? = from?.pollInterval()?.toKotlinDuration()

        /** Input currently required from the client; only for [TaskState.INPUT_REQUIRED]. */
        public var pendingInput: InputRequestBundle? = from?.pendingInput()

        /** Terminal result; required for completed, rejected, and failed tasks. */
        public var result: TaskResult? = from?.result()

        /** Optional protocol metadata. */
        public var meta: Map<String, Any?>? = from?.meta()

        /**
         * Monotonically increasing projection revision. Unset, a snapshot built from a previous one takes
         * its next revision.
         */
        public var revision: Long? = null

        internal fun build(): TaskSnapshot {
            val builder = TaskSnapshot.builder()
            if (from != null) builder.next(from)
            require(revision != null || from != null) { "TaskSnapshot.revision is required" }
            revision?.let { builder.revision(it) }
            return builder
                .taskId(requireNotNull(taskId) { "TaskSnapshot.taskId is required" })
                .status(requireNotNull(status) { "TaskSnapshot.status is required" })
                .statusMessage(statusMessage)
                .createdAt(
                    requireNotNull(
                        createdAt,
                    ) { "TaskSnapshot.createdAt is required" }.toJavaInstant(),
                ).lastUpdatedAt(
                    requireNotNull(
                        lastUpdatedAt,
                    ) { "TaskSnapshot.lastUpdatedAt is required" }.toJavaInstant(),
                ).ttl(ttl?.toJavaDuration())
                .pollInterval(pollInterval?.toJavaDuration())
                .pendingInput(pendingInput)
                .result(result)
                .meta(meta)
                .build()
        }
    }
