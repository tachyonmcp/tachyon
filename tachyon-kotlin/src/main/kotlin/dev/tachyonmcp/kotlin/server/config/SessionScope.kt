// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.api.server.session.SessionIdGenerator
import dev.tachyonmcp.core.server.config.SessionConfig
import dev.tachyonmcp.core.server.session.SessionEventStore
import dev.tachyonmcp.core.server.session.SessionStore
import dev.tachyonmcp.kotlin.server.TachyonDsl
import io.netty.handler.codec.http.HttpRequest
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Session lifecycle and persistence options.
 *
 * Configuring any option here **is** the opt-in — sessions turn on by themselves. [enable] turns
 * them on with the defaults, and
 * [stateless][dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder.stateless] on the server
 * builder states the opt-out. An untouched `session { }` block leaves the server stateless.
 *
 * Mixing [enable] with `stateless()` is last-write-wins and silent; mixing `stateless()` with any
 * option below fails at build time with [SessionConfig.SESSION_OPTIONS_REQUIRE_ENABLED].
 */
@TachyonDsl
public class SessionScope
    @PublishedApi
    internal constructor() {
        private var explicitlyEnabled: Boolean? = null

        /**
         * Turns server-side sessions on with the default options. Redundant when any other option
         * is set — that already enables them.
         */
        public fun enable() {
            explicitlyEnabled = true
        }

        /**
         * Whether session management is enabled.
         *
         * No `ReplaceWith` here on purpose: the replacement differs per direction — `true` becomes
         * [enable], `false` becomes `stateless()` on the server builder.
         */
        @Deprecated(
            "Call enable() to turn sessions on, or stateless() on the server builder for the opt-out",
        )
        public var enabled: Boolean
            get() = explicitlyEnabled == true
            set(value) {
                explicitlyEnabled = value
            }

        /** Session time-to-live duration. */
        public var sessionTtl: Duration? = null

        /** Janitor sweep interval. */
        public var janitorInterval: Duration? = null

        /** Custom immutable session snapshot store. */
        @get:ExperimentalApi(since = "1.0.0-beta.26")
        @set:ExperimentalApi(since = "1.0.0-beta.26")
        public var sessionStore: SessionStore? = null

        /** Custom session event store. */
        @get:ExperimentalApi(since = "1.0.0-beta.26")
        @set:ExperimentalApi(since = "1.0.0-beta.26")
        public var sessionEventStore: SessionEventStore? = null

        /**
         * Session ID generator; `null` (the default) leaves enabled sessions on
         * [SessionIdGenerator.DEFAULT] (`sess_<uuid>`). Assigning one enables sessions, including
         * when the assigned value *is* [SessionIdGenerator.DEFAULT].
         */
        public var sessionIdGenerator: SessionIdGenerator<in HttpRequest>? = null

        /**
         * Lambda-friendly overload, e.g. deriving the id from an authenticated principal:
         * ```kotlin
         * sessionIdGenerator { ctx, req ->
         *     principalFrom(req)?.sessionKey ?: SessionIdGenerator.DEFAULT.generate(ctx, req)
         * }
         * ```
         * The request is always supplied — a generator built here reads it
         * ([SessionIdGenerator.readsRequest] stays `true`), and the dispatcher substitutes an empty
         * request when none was captured, so header lookups return `null` rather than throwing.
         *
         * Do not key sessions off an unauthenticated client header — that invites session
         * fixation and cross-tenant collisions, and a missing header would crash the request thread.
         */
        public fun sessionIdGenerator(generator: (InteractionContext?, HttpRequest) -> String) {
            sessionIdGenerator =
                SessionIdGenerator { ctx, request ->
                    generator(ctx, checkNotNull(request) { REQUEST_REQUIRED })
                }
        }

        @PublishedApi
        internal fun applyTo(builder: SessionConfig.Builder) {
            // Configuring an option enables sessions in Java, so options go on the builder as-is;
            // the Java builder owns the "disabled with options" rejection and its message.
            @Suppress("removal")
            when (explicitlyEnabled) {
                true -> {
                    builder.enabled()
                }

                false -> {
                    @Suppress("DEPRECATION")
                    builder.enabled(false)
                }

                null -> Unit
            }
            sessionTtl?.let { builder.sessionTtl(it.toJavaDuration()) }
            janitorInterval?.let { builder.janitorInterval(it.toJavaDuration()) }
            sessionStore?.let(builder::sessionStore)
            sessionEventStore?.let(builder::sessionEventStore)
            sessionIdGenerator?.let(builder::sessionIdGenerator)
        }

        private companion object {
            /**
             * Unreachable through the HTTP transport, which substitutes an empty request rather
             * than passing `null` to a generator that reads it.
             */
            const val REQUEST_REQUIRED: String =
                "SessionIdGenerator was invoked without a request, but this generator reads it"
        }
    }
