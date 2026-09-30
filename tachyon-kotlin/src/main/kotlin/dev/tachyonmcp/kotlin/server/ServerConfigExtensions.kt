// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.server.config.RuntimeConfig
import dev.tachyonmcp.api.server.config.ServerIdentity
import dev.tachyonmcp.core.server.config.CapabilitiesConfig
import dev.tachyonmcp.core.server.config.NetworkConfig
import dev.tachyonmcp.core.server.config.ObservabilityConfig
import dev.tachyonmcp.core.server.config.ServerConfig
import dev.tachyonmcp.core.server.config.SessionConfig

/** Kotlin property view of the server identity configuration. */
public val ServerConfig.identity: ServerIdentity
    get() = identity()

/** Kotlin property view of the server capabilities configuration. */
public val ServerConfig.capabilities: CapabilitiesConfig
    get() = capabilities()

/** Kotlin property view of the server session configuration. */
public val ServerConfig.session: SessionConfig
    get() = session()

/** Kotlin property view of the server network configuration. */
public val ServerConfig.network: NetworkConfig
    get() = network()

/** Kotlin property view of the server runtime configuration. */
public val ServerConfig.runtime: RuntimeConfig
    get() = runtime()

/** Kotlin property view of the server observability configuration. */
public val ServerConfig.observability: ObservabilityConfig
    @ExperimentalApi
    get() = observability()
