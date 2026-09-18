// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")
@file:JvmName("TachyonServerFactory")

package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Creates a [TachyonServer] and starts the Netty transport on [port].
 * The returned server is bound and listening — close it with [dev.tachyonmcp.core.server.TachyonServer.close].
 *
 * Use `port = 0` for an ephemeral port (discoverable via [dev.tachyonmcp.core.server.TachyonServer.port]).
 *
 * @see buildServer for the non-listening variant
 */
@Suppress("FunctionName")
@OptIn(ExperimentalContracts::class)
@JvmSynthetic
public fun TachyonServer(
    port: Int? = null,
    configure: (@TachyonDsl TachyonServerBuilder).() -> Unit = {},
): TachyonServer {
    contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
    val builder = TachyonServerBuilder().apply(configure)
    builder.applyPort(port)
    return builder.start()
}

/**
 * Builds a fully configured [TachyonServer] **without** starting Netty transport.
 * The returned server is not listening — it supports dynamic registration
 * through its feature registries but has no bound port.
 *
 * Use this for tests that don't need transport, or to set up handlers before
 * binding. To start, create with [TachyonServer] instead.
 */
@OptIn(ExperimentalContracts::class)
@JvmSynthetic
public fun buildServer(
    configure: (@TachyonDsl TachyonServerBuilder).() -> Unit = {},
): TachyonServer {
    contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
    val builder = TachyonServerBuilder().apply(configure)
    return builder.build()
}
