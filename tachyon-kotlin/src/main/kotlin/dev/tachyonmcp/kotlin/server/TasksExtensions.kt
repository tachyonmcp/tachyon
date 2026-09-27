// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.server.features.tasks.Tasks
import dev.tachyonmcp.extensions.tasks.TasksExtension
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
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
