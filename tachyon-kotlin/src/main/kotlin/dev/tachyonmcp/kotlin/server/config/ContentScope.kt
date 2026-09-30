// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.server.domain.AudioContent
import dev.tachyonmcp.api.server.domain.ContentBlock
import dev.tachyonmcp.api.server.domain.EmbeddedResource
import dev.tachyonmcp.api.server.domain.ImageContent
import dev.tachyonmcp.api.server.domain.ResourceContents
import dev.tachyonmcp.api.server.domain.TextContent
import dev.tachyonmcp.kotlin.server.TachyonDsl

/**
 * Collects [dev.tachyonmcp.api.server.domain.ContentBlock]s inside a `content { }` result builder.
 *
 * Each call appends one block; the enclosing scope turns the collected blocks into a result — a
 * [dev.tachyonmcp.api.server.features.tools.ToolResult] for tools, user-role messages for prompts.
 */
@TachyonDsl
public class ContentScope
    internal constructor() {
        internal val blocks: MutableList<ContentBlock> = mutableListOf()

        /** Appends a plain-text block. */
        public fun text(text: String) {
            blocks += TextContent.of(text)
        }

        /** Appends an image block. */
        public fun image(
            data: ByteArray,
            mimeType: String,
        ) {
            blocks += ImageContent.of(data, mimeType)
        }

        /** Appends an audio block. */
        public fun audio(
            data: ByteArray,
            mimeType: String,
        ) {
            blocks += AudioContent.of(data, mimeType)
        }

        /** Appends an embedded resource block. */
        public fun embeddedResource(resource: ResourceContents) {
            blocks += EmbeddedResource.of(resource)
        }

        /** Appends a pre-built content block. */
        public fun add(block: ContentBlock) {
            blocks += block
        }
    }
