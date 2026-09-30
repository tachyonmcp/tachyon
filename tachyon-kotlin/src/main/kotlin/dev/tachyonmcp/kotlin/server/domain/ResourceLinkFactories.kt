// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")
@file:JvmSynthetic

// Copyright (c) 2026 Konstantin Pavlov and contributors.

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.server.domain.ResourceLink
import dev.tachyonmcp.core.server.features.resources.MimeTypes

/**
 * Creates a [ResourceLink] — a lightweight pointer to another resource.
 *
 * @param uri     target resource URI
 * @param name    display name for the link
 * @param mimeType MIME type of the target; defaults to a guess from `uri`'s extension
 */
public fun ResourceLink(
    uri: String,
    name: String,
    mimeType: String? = MimeTypes.guess(uri),
): ResourceLink =
    ResourceLink
        .of(uri, name, mimeType)
