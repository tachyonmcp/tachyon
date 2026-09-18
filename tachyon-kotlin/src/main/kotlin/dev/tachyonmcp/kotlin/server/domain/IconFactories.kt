// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")
@file:JvmName("Icons")

// Copyright (c) 2026 Konstantin Pavlov and contributors.

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.server.domain.Icon
import dev.tachyonmcp.core.server.features.resources.MimeTypes
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Builds an [Icon] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun Icon(block: IconBuilder.() -> Unit): Icon {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return IconBuilder().apply(block).build()
}

/**
 * Creates an [Icon] pointing to an image resource.
 *
 * @param src     image URL or data URI
 * @param mimeType image MIME type (e.g. "image/png"); defaults to a guess from `src`'s extension
 * @param sizes   conventional size labels (e.g. ["16x16", "32x32"]); empty when unspecified
 * @param theme   theme variant ("light", "dark"); null when universal
 */
public fun Icon(
    src: String,
    mimeType: String? = MimeTypes.guess(src),
    sizes: List<String> = emptyList(),
    theme: String? = null,
): Icon =
    Icon
        .of(src, mimeType, sizes, theme)
