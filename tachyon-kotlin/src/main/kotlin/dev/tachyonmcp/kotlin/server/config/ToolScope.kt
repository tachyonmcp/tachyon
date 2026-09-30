// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.api.server.domain.Args
import dev.tachyonmcp.api.server.domain.InputRequest
import dev.tachyonmcp.api.server.features.tasks.Tasks
import dev.tachyonmcp.api.server.features.tools.ToolRequest
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.extensions.tasks.TasksExtension
import dev.tachyonmcp.kotlin.server.TachyonDsl
import dev.tachyonmcp.kotlin.server.requireTasksModule
import org.intellij.lang.annotations.Language
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@TachyonDsl
public class ToolScope
    internal constructor(
        public val ctx: InteractionContext,
        public val request: ToolRequest,
    ) {
        /** Convenience access to the tool call arguments. */
        public val arguments: Args
            get() = request.arguments()

        /**
         * Tasks facade of the server handling this tool call; see [TasksExtension.tasks].
         * Server-scoped: hand it, never [ctx], to background work that outlives the call.
         *
         * @throws IllegalStateException if [TasksExtension] is not registered, or
         *   `tachyon-extensions-tasks` is not on the classpath
         */
        @get:ExperimentalApi
        public val tasks: Tasks by lazy(LazyThreadSafetyMode.NONE) {
            requireTasksModule { TasksExtension.tasks(ctx) }
        }

        /**
         * Returns a [ToolResult] whose structured value is [value], serialized to
         * `structuredContent` by the serde configured in server config at encode time
         * (symmetric with [decode][dev.tachyonmcp.kotlin.server.domain.decode]).
         *
         * When [text] is omitted, no text block is attached and the server emits the
         * serialized JSON as the text content (MCP backwards-compat). Pass [text] to
         * supply a human-readable text block instead.
         *
         * For a pre-serialized JSON payload that skips the configured serde, use
         * [dev.tachyonmcp.api.server.features.tools.ToolResult.raw] directly.
         */
        public fun <T : Any> success(
            value: T,
            text: String? = null,
        ): ToolResult =
            if (text !=
                null
            ) {
                ToolResult.structured(value, text)
            } else {
                ToolResult.structured(value)
            }

        /** Returns a [ToolResult] carrying a single plain-text content block. */
        public fun text(text: String): ToolResult = ToolResult.text(text)

        /**
         * Creates a success result with a pre-serialized JSON payload. The bytes skip the
         * Jackson value-to-tree conversion of ordinary structured values and are parsed once at
         * envelope encoding.
         *
         * @param json a pre-serialized JSON object string
         * @param text the text content for the content block
         */
        @ExperimentalApi
        public fun raw(
            @Language("json") json: String,
            text: String,
        ): ToolResult = ToolResult.raw(json, text)

        /** Returns a [ToolResult] with no structured value and no content blocks. */
        @ExperimentalApi
        public fun empty(): ToolResult = ToolResult.empty()

        /**
         * Returns a failed [ToolResult] carrying a single plain-text error message.
         *
         * Named `fail` rather than `error` because a member `error(String)` here would shadow
         * Kotlin's stdlib [kotlin.error], which throws instead of returning a value.
         */
        @ExperimentalApi
        public fun fail(message: String): ToolResult = ToolResult.error(message)

        /**
         * Returns a failed [ToolResult] built from the content blocks collected in [block], for
         * structured or multi-block error output. Mirrors [content].
         *
         * ```kotlin
         * fail {
         *     text("Validation failed")
         *     text("field 'email' is required")
         * }
         * ```
         */
        @ExperimentalApi
        @OptIn(ExperimentalContracts::class)
        public fun fail(block: ContentScope.() -> Unit): ToolResult {
            contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
            val scope = ContentScope().apply(block)
            return ToolResult.error(*scope.blocks.toTypedArray())
        }

        /**
         * Returns a [ToolResult] signaling that completing the tool call requires additional
         * input from the caller. Build [InputRequest] values with the top-level factories in
         * [dev.tachyonmcp.kotlin.server.domain] ([dev.tachyonmcp.kotlin.server.domain.FormInputRequest],
         * [dev.tachyonmcp.kotlin.server.domain.UrlInputRequest],
         * [dev.tachyonmcp.kotlin.server.domain.RpcMethodRequest]).
         *
         * @param requests the requested inputs, keyed by request id
         * @param state    an opaque state token to echo back with the caller's response
         */
        @ExperimentalApi
        public fun inputRequired(
            requests: Map<String, InputRequest>,
            state: String? = null,
        ): ToolResult = ToolResult.inputRequired(requests, state)

        /**
         * Convenience overload of [inputRequired] taking the requested inputs as `id to request`
         * pairs.
         *
         * ```kotlin
         * inputRequired("email" to FormInputRequest("Enter email", schema))
         * ```
         */
        @ExperimentalApi
        public fun inputRequired(
            vararg requests: Pair<String, InputRequest>,
            state: String? = null,
        ): ToolResult = inputRequired(requests.toMap(), state)

        /**
         * Returns a [ToolResult] built from the content blocks collected in [block]:
         *
         * ```kotlin
         * content {
         *     text("Answer")
         *     image(data, "image/png")
         * }
         * ```
         */
        @OptIn(ExperimentalContracts::class)
        public fun content(block: ContentScope.() -> Unit): ToolResult {
            contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
            val scope = ContentScope().apply(block)
            return ToolResult.content(*scope.blocks.toTypedArray())
        }
    }
