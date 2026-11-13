// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.annotations.LegacyApi
import dev.tachyonmcp.api.server.features.tasks.TaskConnector
import dev.tachyonmcp.api.server.features.tasks.Tasks
import dev.tachyonmcp.core.server.features.Pagination
import dev.tachyonmcp.extensions.tasks.TasksExtension
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import dev.tachyonmcp.core.server.TachyonServer as CoreTachyonServer

private const val TASKS_PACKAGE = "dev/tachyonmcp/extensions/tasks/"

private const val TASKS_MODULE_MISSING =
    "Kotlin tasks API needs dev.tachyonmcp:tachyon-extensions-tasks on the classpath; " +
        "tachyon-kotlin declares it optional, so add it to your build"

/**
 * Tasks facade of this server; see [TasksExtension.tasks].
 *
 * @throws IllegalStateException if [TasksExtension] is not registered, or `tachyon-extensions-tasks`
 *   is not on the classpath
 */
public val CoreTachyonServer.tasks: Tasks
    @ExperimentalApi
    get() = requireTasksModule { TasksExtension.tasks(this) }

/**
 * Runs [block], which touches the optional `tachyon-extensions-tasks` module, turning its absence into
 * an [IllegalStateException] that names the missing dependency.
 */
@OptIn(ExperimentalContracts::class)
internal inline fun <T> requireTasksModule(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return try {
        block()
    } catch (missing: NoClassDefFoundError) {
        if (missing.message?.startsWith(TASKS_PACKAGE) == true) {
            throw IllegalStateException(TASKS_MODULE_MISSING, missing)
        }
        throw missing
    }
}

/**
 * Registers the tasks extension, so tools can hand long-running work to [connector] and clients
 * can poll it through the `tasks` methods. Repeated calls replace scoped settings, including defaults.
 *
 * @param connector system that owns task execution
 * @param configure retention, paging, and polling options
 * @return this builder
 * @throws IllegalStateException if `tachyon-extensions-tasks` is not on the classpath
 */
@OptIn(ExperimentalContracts::class)
@ExperimentalApi
public fun TachyonServerBuilder.tasks(
    connector: TaskConnector,
    configure: (@TachyonDsl TasksScope).() -> Unit = {},
): TachyonServerBuilder {
    contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
    requireTasksModule {
        val scope = TasksScope(connector).apply(configure)
        withExtension(TasksExtension::class.java) { scope.applyTo(this) }
    }
    return this
}

/** Retention, pagination, and polling settings for the tasks extension. */
@TachyonDsl
public class TasksScope
    internal constructor(
        private val connector: TaskConnector,
    ) {
        /** Default page size when a list request omits its limit. */
        public var pageSize: Int = Pagination.DEFAULT_PAGE_SIZE

        /**
         * How long a completed/failed/cancelled task's result stays retrievable before eviction.
         * Zero or negative disables eviction — the result is kept indefinitely.
         */
        public var keepAlive: Duration = TasksExtension.DEFAULT_KEEP_ALIVE.toKotlinDuration()

        /** Suggested client polling interval, or `null` to omit it. */
        public var pollInterval: Duration? = null

        /** Wait between `get` calls serving a blocking legacy `tasks/result` without `awaitResult`. */
        @LegacyApi
        public var resultPollInterval: Duration =
            TasksExtension.DEFAULT_RESULT_POLL_INTERVAL
                .toKotlinDuration()

        internal fun applyTo(builder: TasksExtension.Builder) {
            builder
                .connector(connector)
                .pageSize(pageSize)
                .keepAlive(keepAlive.toJavaDuration())
                .pollInterval(pollInterval?.toJavaDuration())
                .resultPollInterval(resultPollInterval.toJavaDuration())
        }
    }
