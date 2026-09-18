// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.server.config.ServerIdentity
import dev.tachyonmcp.api.server.domain.Icon
import dev.tachyonmcp.kotlin.server.TachyonDsl

@TachyonDsl
public class ServerInfoScope
    internal constructor() {
        /** Server name advertised to clients. */
        public var name: String? = null

        /** Human-readable server title. */
        public var title: String? = null

        /** Server icon set. */
        public val icons: MutableList<Icon> = mutableListOf()

        /** Server version advertised to clients. */
        public var version: String? = null

        /** Human-readable server description. */
        public var description: String? = null

        /** Instructions for how to use the server. */
        public var instructions: String? = null

        /** Server website URL. */
        public var websiteUrl: String? = null

        internal fun applyTo(builder: ServerIdentity.Builder) {
            name?.let(builder::name)
            version?.let(builder::version)
            description?.let(builder::description)
            instructions?.let(builder::instructions)
            title?.let(builder::title)
            websiteUrl?.let(builder::websiteUrl)
            if (!icons.isEmpty()) {
                icons.let(builder::icons)
            }
        }
    }
