// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.server.domain.Annotations
import dev.tachyonmcp.api.server.domain.AudioContent
import dev.tachyonmcp.api.server.domain.BlobResourceContents
import dev.tachyonmcp.api.server.domain.Icon
import dev.tachyonmcp.api.server.domain.ImageContent
import dev.tachyonmcp.api.server.domain.PromptArgument
import dev.tachyonmcp.api.server.domain.Role
import dev.tachyonmcp.api.server.domain.ToolAnnotations
import dev.tachyonmcp.core.server.features.resources.MimeTypes
import dev.tachyonmcp.kotlin.server.TachyonDsl
import dev.tachyonmcp.kotlin.server.config.ResourceScope
import java.util.Base64

/** Builds [dev.tachyonmcp.api.server.domain.Annotations]. */
@TachyonDsl
public class AnnotationsBuilder
    internal constructor() {
        /** Intended roles, or `null` when unrestricted. */
        public var audience: List<Role>? = null

        /** Ordering hint, or `null` for the default. */
        public var priority: Double? = null

        /** RFC-3339 modification timestamp, or `null` when unknown. */
        public var lastModified: String? = null

        internal fun build(): Annotations =
            Annotations.of(
                audience.orEmpty(),
                priority,
                lastModified,
            )
    }

/** Builds an [dev.tachyonmcp.api.server.domain.Icon]. */
@TachyonDsl
public class IconBuilder
    internal constructor() {
        /** Image URL or data URI. */
        public var src: String? = null

        /** Image MIME type. Defaults to a guess from [src]'s extension. */
        public var mimeType: String? = src?.let { MimeTypes.guess(it) }

        /** Conventional image sizes. */
        public var sizes: List<String>? = null

        /** Theme variant. */
        public var theme: String? = null

        internal fun build(): Icon =
            Icon.of(
                requireNotNull(src) { "Icon.src is required" },
                mimeType,
                sizes.orEmpty(),
                theme,
            )
    }

/** Builds a [dev.tachyonmcp.api.server.domain.PromptArgument]. */
@TachyonDsl
public class PromptArgumentBuilder
    internal constructor() {
        /** Argument name. */
        public var name: String? = null

        /** Human-readable title. */
        public var title: String? = null

        /** Argument description. */
        public var description: String? = null

        /** Whether the argument is required. */
        public var required: Boolean? = null

        internal fun build(): PromptArgument =
            PromptArgument.of(
                requireNotNull(name) { "PromptArgument.name is required" },
                title,
                description,
                required,
            )
    }

/** Builds [dev.tachyonmcp.api.server.domain.ToolAnnotations]. */
@TachyonDsl
public class ToolAnnotationsBuilder
    internal constructor() {
        /** Human-readable tool title. */
        public var title: String? = null

        /** Whether the tool avoids state changes. */
        public var readOnlyHint: Boolean? = null

        /** Whether the tool may cause irreversible changes. */
        public var destructiveHint: Boolean? = null

        /** Whether retrying with the same arguments is safe. */
        public var idempotentHint: Boolean? = null

        /** Whether the tool may interact with external systems. */
        public var openWorldHint: Boolean? = null

        internal fun build(): ToolAnnotations =
            ToolAnnotations.of(
                title,
                readOnlyHint,
                destructiveHint,
                idempotentHint,
                openWorldHint,
            )
    }

/** Builds [dev.tachyonmcp.api.server.domain.ImageContent]. */
@TachyonDsl
public class ImageContentBuilder
    internal constructor() {
        /** Raw image bytes. */
        public var data: ByteArray? = null

        /** Base64-encoded image bytes. */
        @Deprecated(
            "Base64-encoded String input is deprecated; assign raw bytes to `data` instead.",
        )
        public var dataBase64: String?
            get() = data?.let { Base64.getEncoder().encodeToString(it) }
            set(value) {
                data = value?.let { Base64.getDecoder().decode(it) }
            }

        /** Image MIME type. */
        public var mimeType: String? = null

        /** Optional presentation hints. */
        public var annotations: Annotations? = null

        /** Optional content metadata. */
        public var meta: Map<String, Any>? = null

        internal fun build(): ImageContent =
            ImageContent.of(
                requireNotNull(data) { "ImageContent.data is required" },
                requireNotNull(mimeType) { "ImageContent.mimeType is required" },
                annotations,
                meta,
            )
    }

/** Builds [dev.tachyonmcp.api.server.domain.AudioContent]. */
@TachyonDsl
public class AudioContentBuilder
    internal constructor() {
        /** Raw audio bytes. */
        public var data: ByteArray? = null

        /** Base64-encoded audio bytes. */
        @Deprecated(
            "Base64-encoded String input is deprecated; assign raw bytes to `data` instead.",
        )
        public var dataBase64: String?
            get() = data?.let { Base64.getEncoder().encodeToString(it) }
            set(value) {
                data = value?.let { Base64.getDecoder().decode(it) }
            }

        /** Audio MIME type. */
        public var mimeType: String? = null

        /** Optional presentation hints. */
        public var annotations: Annotations? = null

        /** Optional content metadata. */
        public var meta: Map<String, Any>? = null

        internal fun build(): AudioContent =
            AudioContent.of(
                requireNotNull(data) { "AudioContent.data is required" },
                requireNotNull(mimeType) { "AudioContent.mimeType is required" },
                annotations,
                meta,
            )
    }

/** Builds [dev.tachyonmcp.api.server.domain.BlobResourceContents]. */
@TachyonDsl
public class BlobResourceContentsBuilder
    internal constructor(
        scope: ResourceScope? = null,
    ) {
        /** Resource URI. */
        public var uri: String? = scope?.uri

        /** Raw resource bytes. */
        public var data: ByteArray? = null

        /** Alias for [data]; kept for the pre-rename `blob` property name. */
        @Deprecated("Renamed to `data`.", ReplaceWith("data"))
        public var blob: ByteArray? by ::data

        /** Base64-encoded resource bytes. */
        @Deprecated(
            "Base64-encoded String input is deprecated; assign raw bytes to `data` instead.",
        )
        public var dataBase64: String?
            get() = data?.let { Base64.getEncoder().encodeToString(it) }
            set(value) {
                data = value?.let { Base64.getDecoder().decode(it) }
            }

        /** Resource MIME type. Defaults to the registered template MIME type, or a guess from [uri]'s extension. */
        public var mimeType: String? = scope?.registeredMimeType ?: uri?.let { MimeTypes.guess(it) }

        /** Optional resource metadata. */
        public var meta: Map<String, Any> = emptyMap()

        internal fun build(): BlobResourceContents =
            BlobResourceContents.of(
                requireNotNull(uri) {
                    "BlobResourceContents.uri is required: set it explicitly, or build inside a " +
                        "resource/template handler where BlobResourceContents { } defaults it from the request"
                },
                requireNotNull(data) { "BlobResourceContents.data is required" },
                mimeType,
                meta,
            )
    }
