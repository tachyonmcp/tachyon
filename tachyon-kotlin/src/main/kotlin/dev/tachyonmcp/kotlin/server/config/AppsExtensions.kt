// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.extensions.apps.AppsExtension
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Registers MCP Apps views (SEP-1865) with the apps extension. Each [AppsScope.app] serves one
 * `ui://` HTML document; link a tool to it with `tool(name, meta = AppsExtension.toolMeta(uri)) { }`.
 * Repeated calls add views.
 *
 * Requires `tachyon-extensions-apps` on the classpath; it is an optional dependency.
 *
 * @param configure the views to serve
 * @return this builder
 */
@OptIn(ExperimentalContracts::class)
@ExperimentalApi
public fun TachyonServerBuilder.apps(
    configure: (@TachyonDsl AppsScope).() -> Unit,
): TachyonServerBuilder {
    contract { callsInPlace(configure, InvocationKind.AT_MOST_ONCE) }
    withExtension(AppsExtension::class.java) { AppsScope(this).configure() }
    return this
}
