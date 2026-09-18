// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")

package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.api.server.domain.BlobResourceContents
import dev.tachyonmcp.api.server.domain.TextResourceContents
import dev.tachyonmcp.api.server.domain.UriTemplateValue
import dev.tachyonmcp.api.server.features.resources.ResourceRequest
import dev.tachyonmcp.kotlin.server.TachyonDsl
import dev.tachyonmcp.kotlin.server.domain.BlobResourceContentsBuilder
import dev.tachyonmcp.kotlin.server.domain.TextResourceContentsBuilder
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Receiver for a static or templated resource handler.
 */
@TachyonDsl
public open class ResourceScope
    internal constructor(
        /** Interaction context for the resource read. */
        public val ctx: InteractionContext,
        /** Full resource request, including URI-template data and request metadata. */
        public val request: ResourceRequest,
        internal val registeredMimeType: String?,
    ) {
        /** Requested resource URI. */
        public val uri: String
            get() = request.uri()

        /** Parsed URI-template parameters, or an empty map for a static resource. */
        public val params: Map<String, UriTemplateValue>
            get() = request.params()

        /** Original URI template, or `null` for a static resource. */
        public open val uriTemplate: String?
            get() = request.uriTemplate()

        /**
         * Builds text resource contents using this request's URI and the registered MIME type as
         * defaults.
         */
        @OptIn(ExperimentalContracts::class)
        public fun TextResourceContents(
            block: (@TachyonDsl TextResourceContentsBuilder).() -> Unit,
        ): TextResourceContents {
            contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
            return TextResourceContentsBuilder(this).apply(block).build()
        }

        /**
         * Builds binary resource contents using this request's URI and the registered MIME type as
         * defaults.
         */
        @OptIn(ExperimentalContracts::class)
        public fun BlobResourceContents(
            block: (@TachyonDsl BlobResourceContentsBuilder).() -> Unit,
        ): BlobResourceContents {
            contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
            return BlobResourceContentsBuilder(this).apply(block).build()
        }
    }
