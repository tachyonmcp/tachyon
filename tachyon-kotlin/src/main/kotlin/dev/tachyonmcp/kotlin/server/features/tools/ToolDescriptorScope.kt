// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")

package dev.tachyonmcp.kotlin.server.features.tools

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.domain.Icon
import dev.tachyonmcp.api.server.domain.ToolAnnotations
import dev.tachyonmcp.api.server.features.tasks.TaskSupport
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor
import dev.tachyonmcp.core.server.json.Jackson3JsonFactory
import dev.tachyonmcp.kotlin.server.TachyonDsl
import dev.tachyonmcp.kotlin.server.json.toJsonSchema
import kotlinx.serialization.json.JsonObject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@TachyonDsl
public class ToolDescriptorScope
    internal constructor() {
        public var name: String? = null
        public var title: String? = null
        public var description: String? = null
        public var inputSchema: JsonSchema? = null
        public var outputSchema: JsonSchema? = null
        public var taskSupport: TaskSupport? = null
        public var annotations: ToolAnnotations? = null
        public var icons: List<Icon>? = null

        /** Extension-ownership marker; set by extension implementations only, not by ordinary tools. */
        @ExperimentalApi
        public var extensionId: String? = null
        public var meta: Map<String, Any>? = null

        public fun inputSchema(json: String) {
            inputSchema =
                Jackson3JsonFactory.INSTANCE
                    .toJsonSchema(json)
                    .orElseThrow {
                        error(
                            "Jackson3JsonFactory did not produce a schema for: $json",
                        )
                    }
        }

        public fun outputSchema(json: String) {
            outputSchema =
                Jackson3JsonFactory.INSTANCE.toJsonSchema(json).orElseThrow {
                    error(
                        "Jackson3JsonFactory did not produce a schema for: $json",
                    )
                }
        }

        /** Sets the input schema from a kotlinx-serialization [JsonObject]. */
        public fun inputSchema(json: JsonObject) {
            inputSchema = json.toJsonSchema()
        }

        /** Sets the output schema from a kotlinx-serialization [JsonObject]. */
        public fun outputSchema(json: JsonObject) {
            outputSchema = json.toJsonSchema()
        }

        internal fun build(): ToolDescriptor {
            val n = requireNotNull(name) { "ToolDescriptor.name is required" }
            val builder =
                ToolDescriptor
                    .builder()
                    .name(
                        n,
                    )
            title?.let(builder::title)
            description?.let(builder::description)
            inputSchema?.let(builder::inputSchema)
            outputSchema?.let(builder::outputSchema)
            taskSupport?.let(builder::taskSupport)
            annotations?.let(builder::annotations)
            icons?.let(builder::icons)
            extensionId?.let(builder::extensionId)
            meta?.let(builder::meta)
            return builder.build()
        }
    }

@OptIn(ExperimentalContracts::class)
public fun toolDescriptor(
    name: String,
    configure: ToolDescriptorScope.() -> Unit = {},
): ToolDescriptor {
    contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
    return ToolDescriptorScope()
        .apply {
            this.name = name
            configure()
        }.build()
}

/** Builds a [ToolDescriptor] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun ToolDescriptor(block: ToolDescriptorScope.() -> Unit): ToolDescriptor {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return ToolDescriptorScope().apply(block).build()
}

/**
 * Builds a [ToolDescriptor] from the full set of optional attributes exposed by
 * [ToolDescriptor.Builder]. Shared by the flat `tool(...)` / `registerTool(...)` overloads so
 * every one of them accepts the same attributes.
 */
@PublishedApi
internal fun toolDescriptorOf(
    name: String,
    description: String?,
    title: String?,
    inputSchema: JsonSchema?,
    outputSchema: JsonSchema?,
    taskSupport: TaskSupport?,
    annotations: ToolAnnotations?,
    icons: List<Icon>?,
    meta: Map<String, Any>?,
): ToolDescriptor =
    toolDescriptor(name) {
        this.title = title
        this.description = description
        this.inputSchema = inputSchema
        this.outputSchema = outputSchema
        this.taskSupport = taskSupport
        this.annotations = annotations
        this.icons = icons
        this.meta = meta
    }
