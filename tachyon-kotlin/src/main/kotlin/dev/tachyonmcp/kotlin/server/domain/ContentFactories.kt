// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName", "TooManyFunctions")
@file:JvmName("ContentBlocks")
@file:JvmSynthetic

// Copyright (c) 2026 Konstantin Pavlov and contributors.

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.server.domain.Annotations
import dev.tachyonmcp.api.server.domain.AudioContent
import dev.tachyonmcp.api.server.domain.EmbeddedResource
import dev.tachyonmcp.api.server.domain.ImageContent
import dev.tachyonmcp.api.server.domain.ResourceContents
import dev.tachyonmcp.api.server.domain.TextContent
import java.util.Base64
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Creates a [TextContent] block — plain text provided to or from an LLM.
 *
 * @param text        the text content
 * @param meta        optional request-level metadata; null to omit
 * @param annotations optional presentation hints (audience, priority, etc.)
 */
public fun TextContent(
    text: String,
    meta: Map<String, Any>? = null,
    annotations: Annotations? = null,
): TextContent =
    TextContent
        .of(text, meta, annotations)

/** Builds [ImageContent] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun ImageContent(block: ImageContentBuilder.() -> Unit): ImageContent {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return ImageContentBuilder().apply(block).build()
}

/** Builds [AudioContent] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun AudioContent(block: AudioContentBuilder.() -> Unit): AudioContent {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return AudioContentBuilder().apply(block).build()
}

/**
 * Creates an [ImageContent] block — raw image bytes.
 *
 * @param data        the image bytes
 * @param mimeType    image format (e.g. "image/png")
 * @param annotations optional presentation hints
 * @param meta        optional request-level metadata; null to omit
 */
public fun ImageContent(
    data: ByteArray,
    mimeType: String,
    annotations: Annotations? = null,
    meta: Map<String, Any>? = null,
): ImageContent =
    ImageContent.of(
        data,
        mimeType,
        annotations,
        meta,
    )

/**
 * Creates an [ImageContent] block — base64-encoded image data.
 *
 * @param data        base64-encoded image bytes
 * @param mimeType    image format (e.g. "image/png")
 * @param annotations optional presentation hints
 * @param meta        optional request metadata; null to omit
 */
@Deprecated(
    message = "Base64-encoded String input is deprecated; pass raw bytes instead.",
    replaceWith =
        ReplaceWith(
            "ImageContent(Base64.getDecoder().decode(data), mimeType, annotations, meta)",
            "java.util.Base64",
        ),
)
public fun ImageContent(
    data: String,
    mimeType: String,
    annotations: Annotations? = null,
    meta: Map<String, Any>? = null,
): ImageContent =
    ImageContent.of(
        Base64.getDecoder().decode(data),
        mimeType,
        annotations,
        meta,
    )

/**
 * Creates an [AudioContent] block — raw audio bytes.
 *
 * @param data        the audio bytes
 * @param mimeType    audio format (e.g. "audio/mp3")
 * @param annotations optional presentation hints
 * @param meta        optional request metadata; null to omit
 */
public fun AudioContent(
    data: ByteArray,
    mimeType: String,
    annotations: Annotations? = null,
    meta: Map<String, Any>? = null,
): AudioContent =
    AudioContent.of(
        data,
        mimeType,
        annotations,
        meta,
    )

/**
 * Creates an [AudioContent] block — base64-encoded audio data.
 *
 * @param data        base64-encoded audio bytes
 * @param mimeType    audio format (e.g. "audio/mp3")
 * @param annotations optional presentation hints
 * @param meta        optional request metadata; null to omit
 */
@Deprecated(
    message = "Base64-encoded String input is deprecated; pass raw bytes instead.",
    replaceWith =
        ReplaceWith(
            "AudioContent(Base64.getDecoder().decode(data), mimeType, annotations, meta)",
            "java.util.Base64",
        ),
)
public fun AudioContent(
    data: String,
    mimeType: String,
    annotations: Annotations? = null,
    meta: Map<String, Any>? = null,
): AudioContent =
    AudioContent.of(
        Base64.getDecoder().decode(data),
        mimeType,
        annotations,
        meta,
    )

/**
 * Creates an [EmbeddedResource] — a complete resource inlined within a result.
 *
 * @param resource    the resource contents (text or blob)
 * @param annotations optional presentation hints
 * @param meta        optional request metadata; null to omit
 */
public fun EmbeddedResource(
    resource: ResourceContents,
    annotations: Annotations? = null,
    meta: Map<String, Any>? = null,
): EmbeddedResource =
    EmbeddedResource
        .of(resource, annotations, meta)
