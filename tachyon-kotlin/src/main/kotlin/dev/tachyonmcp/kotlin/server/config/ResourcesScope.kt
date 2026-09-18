// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.core.server.config.ResourcesConfig
import dev.tachyonmcp.core.server.features.Pagination
import dev.tachyonmcp.kotlin.server.TachyonDsl

@TachyonDsl
public class ResourcesScope
    internal constructor() {
        /** Enablement mode: `ON`, `OFF`, or `AUTO`. */
        public var mode: Mode = Mode.AUTO

        /** Whether to advertise list-changed notifications. */
        public var listChanged: Boolean = false

        /** Default page size when a list request omits its limit. */
        public var pageSize: Int = Pagination.DEFAULT_PAGE_SIZE

        /** Whether resource subscriptions (`resources/subscribe`) are supported. */
        public var subscribe: Boolean = false

        internal fun toConfig(): ResourcesConfig =
            ResourcesConfig
                .builder()
                .mode(mode)
                .listChanged(listChanged)
                .pageSize(pageSize)
                .subscribe(subscribe)
                .build()
    }
