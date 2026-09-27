// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.LegacyApi
import dev.tachyonmcp.api.server.features.tasks.TaskConnector
import dev.tachyonmcp.core.server.features.Pagination
import dev.tachyonmcp.extensions.tasks.TasksExtension
import dev.tachyonmcp.kotlin.server.TachyonDsl
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

@TachyonDsl
public class TasksScope
    internal constructor(
        private val connector: TaskConnector,
    ) {
        /** Default page size when a list request omits its limit. */
        public var pageSize: Int = Pagination.DEFAULT_PAGE_SIZE

        /**
         * How long a completed/failed/cancelled task's result stays retrievable before eviction.
         * Zero or negative disables eviction — the result is kept indefinitely.
         */
        public var keepAlive: Duration = TasksExtension.DEFAULT_KEEP_ALIVE.toKotlinDuration()

        /** Suggested client polling interval, or `null` to omit it. */
        public var pollInterval: Duration? = null

        /** Wait between `get` calls serving a blocking legacy `tasks/result` without `awaitResult`. */
        @LegacyApi
        public var resultPollInterval: Duration =
            TasksExtension.DEFAULT_RESULT_POLL_INTERVAL
                .toKotlinDuration()

        /** Longest a blocking legacy `tasks/result` polls `get` before giving up. */
        @LegacyApi
        public var resultMaxWait: Duration =
            TasksExtension.DEFAULT_RESULT_MAX_WAIT
                .toKotlinDuration()

        internal fun applyTo(builder: TasksExtension.Builder) {
            builder
                .connector(connector)
                .pageSize(pageSize)
                .keepAlive(keepAlive.toJavaDuration())
                .pollInterval(pollInterval?.toJavaDuration())
                .resultPollInterval(resultPollInterval.toJavaDuration())
                .resultMaxWait(resultMaxWait.toJavaDuration())
        }
    }
