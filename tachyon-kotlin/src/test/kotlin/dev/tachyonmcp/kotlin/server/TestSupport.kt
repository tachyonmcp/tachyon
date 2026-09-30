// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.core.server.TachyonServer
import dev.tachyonmcp.core.server.internal.ServerEngine
import dev.tachyonmcp.core.server.session.DefaultDispatchContext
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
internal fun <T> withStatelessContext(block: (InteractionContext) -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return TachyonServer.builder().build().use { server ->
        block(DefaultDispatchContext.stateless(server as ServerEngine))
    }
}
