// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.core.server.config.ObservabilityConfig
import dev.tachyonmcp.core.server.observability.ObservationListener
import dev.tachyonmcp.kotlin.server.TachyonDsl
import java.time.Duration
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.toJavaDuration

/** Kotlin DSL scope for slow-request diagnostics and passive MCP observation. */
@TachyonDsl
@ExperimentalApi
public class ObservabilityScope
    internal constructor() {
        private var slowRequestLogging: Boolean? = null
        private var slowRequestThreshold: Duration? = null
        private val listeners = mutableListOf<ObservationListener>()
        private var payloadCapture: PayloadCaptureScope? = null

        /** Enables or disables slow-request diagnostics. */
        public fun slowRequestLogging(enabled: Boolean = true) {
            slowRequestLogging = enabled
        }

        /** Enables slow-request diagnostics with the given threshold. */
        public fun slowRequestLogging(threshold: Duration) {
            slowRequestLogging = true
            slowRequestThreshold = threshold
        }

        /** Enables slow-request diagnostics with the given Kotlin duration threshold. */
        public fun slowRequestLogging(threshold: kotlin.time.Duration) {
            slowRequestLogging = true
            slowRequestThreshold = threshold.toJavaDuration()
        }

        /** Registers an observation listener. */
        public fun listener(listener: ObservationListener) {
            listeners += listener
        }

        /** Configures opt-in request/response payload and exception-detail capture. */
        @OptIn(ExperimentalContracts::class)
        public fun payloadCapture(configure: PayloadCaptureScope.() -> Unit) {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            payloadCapture = PayloadCaptureScope().apply(configure)
        }

        internal fun applyTo(builder: ObservabilityConfig.Builder) {
            slowRequestLogging?.let(builder::slowRequestLogging)
            slowRequestThreshold?.let(builder::slowRequestThreshold)
            listeners.forEach(builder::listener)
            payloadCapture?.let { scope -> builder.payloadCapture(scope::applyTo) }
        }
    }
