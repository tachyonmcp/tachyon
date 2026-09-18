// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.core.server.config.ObservabilityConfig
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.time.Duration
import kotlin.time.toJavaDuration

@TachyonDsl
@Deprecated("Use observability { } instead")
public class MonitoringScope
    internal constructor() {
        public var slowRequestLogging: Boolean? = null

        public var slowRequestThreshold: Duration? = null

        internal fun applyTo(builder: ObservabilityConfig.Builder) {
            slowRequestLogging?.let { builder.slowRequestLogging(it) }
            slowRequestThreshold?.let { builder.slowRequestThreshold(it.toJavaDuration()) }
        }
    }
