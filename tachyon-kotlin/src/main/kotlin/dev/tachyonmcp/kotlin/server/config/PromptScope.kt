// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.api.server.domain.Args
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.api.server.features.prompts.PromptRequest
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@TachyonDsl
public class PromptScope
    internal constructor(
        public val ctx: InteractionContext,
        public val request: PromptRequest,
    ) {
        /**
         * Convenience access to the prompt arguments.
         */
        public val arguments: Args
            get() = request.arguments()

        /**
         * Builds a list of user-role [dev.tachyonmcp.api.server.domain.PromptMessage]s
         * from the content blocks collected in
         * [block] — one message per block. For explicit roles use
         * [dev.tachyonmcp.kotlin.server.domain.PromptMessage].
         *
         * ```kotlin
         * content {
         *     text("Summarize this")
         *     image(data, "image/png")
         * }
         * ```
         */
        @OptIn(ExperimentalContracts::class)
        public fun content(block: ContentScope.() -> Unit): List<PromptMessage> {
            contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
            return ContentScope().apply(block).blocks.map { PromptMessage.user(it) }
        }
    }
