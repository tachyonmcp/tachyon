// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")

// Copyright (c) 2026 Konstantin Pavlov and contributors.

package dev.tachyonmcp.kotlin.server.features.resources

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.server.domain.Annotations
import dev.tachyonmcp.api.server.domain.Icon
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Builds a [dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor]. */
@TachyonDsl
public class ResourceTemplateDescriptorBuilder
    internal constructor() {
        /** Template name. */
        public var name: String? = null

        /** URI-template pattern. */
        public var uriTemplate: String? = null

        /** Template description. */
        public var description: String? = null

        /** Resource MIME type. */
        public var mimeType: String? = null

        /** Human-readable title. */
        public var title: String? = null

        /** Extension-ownership marker; set by extension implementations only, not by ordinary templates. */
        @ExperimentalApi
        public var extensionId: String? = null

        /** Optional presentation hints. */
        public var annotations: Annotations? = null

        /** Associated icons. */
        public var icons: List<Icon> = emptyList()

        /** Protocol extension metadata. */
        public var meta: Map<String, Any>? = null

        internal fun build(): ResourceTemplateDescriptor =
            ResourceTemplateDescriptor
                .builder()
                .name(
                    requireNotNull(name) {
                        "ResourceTemplateDescriptor.name is required"
                    },
                ).uriTemplate(
                    requireNotNull(uriTemplate) {
                        "ResourceTemplateDescriptor.uriTemplate is required"
                    },
                ).description(description)
                .mimeType(mimeType)
                .title(title)
                .annotations(annotations)
                .icons(icons)
                .extensionId(extensionId)
                .meta(meta)
                .build()
    }

/** Builds a [ResourceTemplateDescriptor] with a receiver DSL. */
@OptIn(ExperimentalContracts::class)
public fun ResourceTemplateDescriptor(
    block: ResourceTemplateDescriptorBuilder.() -> Unit,
): ResourceTemplateDescriptor {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return ResourceTemplateDescriptorBuilder().apply(block).build()
}
