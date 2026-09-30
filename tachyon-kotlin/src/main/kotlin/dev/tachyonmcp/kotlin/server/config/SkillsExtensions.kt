// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation
import dev.tachyonmcp.extensions.skills.SkillsExtension
import dev.tachyonmcp.extensions.skills.SkillsRegistry
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration

/**
 * Registers [registry] with the skills extension and configures its cache hints and negotiation.
 * Add further sources with [SkillsScope.registry]. Repeated calls append registries and replace
 * settings with the new block's values, including defaults.
 *
 * Requires `tachyon-extensions-skills` on the classpath; it is an optional dependency.
 *
 * @param registry source of skills
 * @param configure cache hints and client negotiation policy
 * @return this builder
 */
@OptIn(ExperimentalContracts::class)
@ExperimentalApi
public fun TachyonServerBuilder.skills(
    registry: SkillsRegistry,
    configure: (@TachyonDsl SkillsScope).() -> Unit = {},
): TachyonServerBuilder {
    contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
    var scope: SkillsScope? = null
    withExtension(SkillsExtension::class.java) {
        registry(registry)
        scope = SkillsScope(this)
    }
    checkNotNull(scope).apply(configure).applyTo()
    return this
}

/** Cache hints, negotiation policy, and additional sources for the skills extension. */
@TachyonDsl
@ExperimentalApi
public class SkillsScope
    internal constructor(
        private val delegate: SkillsExtension.Builder,
    ) {
        /** Listing freshness hint; zero means always stale. Must be non-negative. */
        public var cacheTtl: Duration = Duration.ZERO

        /** Listing cache scope: `public` or `private`. */
        public var cacheScope: String = "public"

        /** Whether clients must declare the skills extension before calling its methods. */
        public var negotiation: ExtensionNegotiation = ExtensionNegotiation.OPTIONAL

        /** Adds another source of skills to this extension. */
        public fun registry(registry: SkillsRegistry) {
            delegate.registry(registry)
        }

        internal fun applyTo() {
            require(!cacheTtl.isNegative()) { "cacheTtl must be non-negative, was $cacheTtl" }
            delegate
                .cacheTtlMs(cacheTtl.inWholeMilliseconds)
                .cacheScope(cacheScope)
                .negotiation(negotiation)
        }
    }
