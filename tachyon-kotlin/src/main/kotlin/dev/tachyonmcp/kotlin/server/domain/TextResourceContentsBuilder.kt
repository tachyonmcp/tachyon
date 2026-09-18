// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.server.domain.TextResourceContents
import dev.tachyonmcp.core.server.features.resources.MimeTypes
import dev.tachyonmcp.kotlin.server.TachyonDsl
import dev.tachyonmcp.kotlin.server.config.ResourceScope
import dev.tachyonmcp.kotlin.server.config.TemplateScope

/**
 * Builds [dev.tachyonmcp.api.server.domain.TextResourceContents] for a matched resource template.
 *
 * The requested URI and registered template MIME type are used as defaults.
 */
@TachyonDsl
public class TextResourceContentsBuilder
    internal constructor(
        private val scope: ResourceScope? = null,
    ) {
        /** Resource URI. Defaults to the requested URI. */
        public var uri: String? = scope?.uri

        /** Resource MIME type. Defaults to the registered template MIME type, or a guess from [uri]'s extension. */
        public var mimeType: String? = scope?.registeredMimeType ?: uri?.let { MimeTypes.guess(it) }

        /** Text payload. */
        public var text: String? = null

        /** Optional resource metadata. */
        public var meta: Map<String, Any>? = null

        /**
         * Returns a scalar URI-template parameter.
         *
         * @param name template parameter name
         */
        public fun param(name: String): String =
            requireNotNull(scope as? TemplateScope) {
                "URI-template parameters require a TemplateScope"
            }.param(name)

        /**
         * Returns a sequence URI-template parameter.
         *
         * @param name template parameter name
         */
        public fun sequence(name: String): List<String> =
            requireNotNull(
                scope as? TemplateScope,
            ) { "URI-template parameters require a TemplateScope" }.sequence(name)

        internal fun build(): TextResourceContents =
            TextResourceContents.of(
                requireNotNull(uri) {
                    "TextResourceContents.uri is required: set it explicitly, or build inside a " +
                        "resource/template handler where TextResourceContents { } defaults it from the request"
                },
                requireNotNull(text) { "TextResourceContents.text is required" },
                mimeType,
                meta,
            )
    }
