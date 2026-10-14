// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.extensions.apps.AppMeta
import dev.tachyonmcp.extensions.apps.AppResource
import dev.tachyonmcp.extensions.apps.AppsExtension
import dev.tachyonmcp.kotlin.server.TachyonDsl

/** Views served by the apps extension. */
@TachyonDsl
@ExperimentalApi
public class AppsScope
    internal constructor(
        private val delegate: AppsExtension.Builder,
    ) {
        /**
         * Serves one view at [uri] (must start with `ui://`). [html] runs on every `resources/read`
         * and must return a self-contained HTML5 document; [meta] declares its CSP, permissions and
         * rendering preferences on both the listing entry and the read content.
         */
        public fun app(
            uri: String,
            name: String,
            title: String? = null,
            description: String? = null,
            meta: AppMeta = AppMeta.NONE,
            html: () -> String,
        ) {
            delegate.app(
                AppResource
                    .builder()
                    .uri(uri)
                    .name(name)
                    .title(title)
                    .description(description)
                    .meta(meta)
                    .html(html)
                    .build(),
            )
        }
    }
