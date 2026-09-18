// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")

package dev.tachyonmcp.kotlin.server.features.completions

import dev.tachyonmcp.api.server.features.completions.CompletionResult
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Builds a [dev.tachyonmcp.api.server.features.completions.CompletionResult].
 *
 * Deliberately not a `@TachyonDsl` receiver: nothing nests inside this leaf builder, so the marker
 * would only hide the enclosing [dev.tachyonmcp.kotlin.server.config.CompletionScope] and block
 * `CompletionResult { values = candidates.filter { it.startsWith(argumentValue) } }`.
 */
public class CompletionResultBuilder
    internal constructor() {
        /** Candidate values ranked by relevance. */
        public var values: List<String> = emptyList()

        /** Total number of matches, or `null` if unknown. */
        public var total: Long? = null

        /** Whether additional results exist beyond [values], or `null` if unknown. */
        public var hasMore: Boolean? = null

        /** Optional protocol extension metadata. */
        public var meta: Map<String, Any>? = null

        internal fun build(): CompletionResult =
            CompletionResult
                .builder()
                .values(values)
                .total(total)
                .hasMore(hasMore)
                .meta(meta)
                .build()
    }

/** Builds a [CompletionResult] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun CompletionResult(block: CompletionResultBuilder.() -> Unit): CompletionResult {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return CompletionResultBuilder().apply(block).build()
}
