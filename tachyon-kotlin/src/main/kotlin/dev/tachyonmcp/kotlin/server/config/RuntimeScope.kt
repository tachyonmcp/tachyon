// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.server.config.RuntimeConfig
import dev.tachyonmcp.kotlin.server.TachyonDsl
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.toJavaDuration

@TachyonDsl
public class RuntimeScope
    internal constructor() {
        /** Grace period for graceful shutdown. */
        public var shutdownGracePeriod: Duration? = null

        /** Timeout for pending requests sent to the client (default 60s). */
        public var requestTimeout: Duration? = null

        /**
         * Clock used for task timestamps and TTL/expiry checks (default the system clock). Set a
         * fixed or controllable [Clock] in tests that need deterministic timing.
         */
        public var clock: Clock? = null

        internal fun applyTo(builder: RuntimeConfig.Builder) {
            shutdownGracePeriod?.let { builder.shutdownGracePeriod(it.toJavaDuration()) }
            requestTimeout?.let { builder.requestTimeout(it.toJavaDuration()) }
            clock?.let { builder.clock(it) }
        }
    }
