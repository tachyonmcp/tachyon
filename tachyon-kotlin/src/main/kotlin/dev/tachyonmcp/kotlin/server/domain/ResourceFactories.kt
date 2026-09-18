// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")
@file:JvmName("ResourceContentsFactory")

// Copyright (c) 2026 Konstantin Pavlov and contributors.

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.server.domain.BlobResourceContents
import dev.tachyonmcp.api.server.domain.TextResourceContents
import dev.tachyonmcp.core.server.features.resources.MimeTypes
import java.util.Base64
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Builds [TextResourceContents] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun TextResourceContents(
    block: TextResourceContentsBuilder.() -> Unit,
): TextResourceContents {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return TextResourceContentsBuilder().apply(block).build()
}

/** Builds [BlobResourceContents] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun BlobResourceContents(
    block: BlobResourceContentsBuilder.() -> Unit,
): BlobResourceContents {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return BlobResourceContentsBuilder().apply(block).build()
}

/**
 * Creates [TextResourceContents] — text-based resource data returned by a handler.
 *
 * @param uri      originating resource URI
 * @param text     the actual text content
 * @param mimeType MIME type of the text (e.g. "application/json"); defaults to a guess from `uri`'s extension
 * @param meta     optional request-level metadata; null to omit
 * @author Konstantin Pavlov
 */
public fun TextResourceContents(
    uri: String,
    text: String,
    mimeType: String? = MimeTypes.guess(uri),
    meta: Map<String, Any>? = null,
): TextResourceContents =
    TextResourceContents.of(
        uri,
        text,
        mimeType,
        meta,
    )

/**
 * Creates [BlobResourceContents] — binary resource data.
 *
 * @param uri      originating resource URI
 * @param blob     the binary content
 * @param mimeType MIME type of the binary data; defaults to a guess from `uri`'s extension
 * @param meta     optional request-level metadata; defaults to empty map
 * @author Konstantin Pavlov
 */
public fun BlobResourceContents(
    uri: String,
    blob: ByteArray,
    mimeType: String? = MimeTypes.guess(uri),
    meta: Map<String, Any> = emptyMap(),
): BlobResourceContents =
    BlobResourceContents.of(
        uri,
        blob,
        mimeType,
        meta,
    )

/**
 * Creates [BlobResourceContents] — binary resource data encoded as base64.
 *
 * @param uri      originating resource URI
 * @param blob     base64-encoded binary content
 * @param mimeType MIME type of the binary data; defaults to a guess from `uri`'s extension
 * @param meta     optional request-level metadata; defaults to empty map
 * @author Konstantin Pavlov
 */
@Deprecated(
    message = "Base64-encoded String input is deprecated; pass raw bytes instead.",
    replaceWith =
        ReplaceWith(
            "BlobResourceContents(uri, Base64.getDecoder().decode(blob), mimeType, meta)",
            "java.util.Base64",
        ),
)
public fun BlobResourceContents(
    uri: String,
    blob: String,
    mimeType: String? = MimeTypes.guess(uri),
    meta: Map<String, Any> = emptyMap(),
): BlobResourceContents =
    BlobResourceContents.of(
        uri,
        Base64.getDecoder().decode(blob),
        mimeType,
        meta,
    )
