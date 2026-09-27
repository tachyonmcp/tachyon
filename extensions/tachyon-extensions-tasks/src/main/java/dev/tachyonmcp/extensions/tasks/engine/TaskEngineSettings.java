/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import dev.tachyonmcp.api.annotations.LegacyApi;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Validated settings of one {@link TaskEngine}.
 *
 * @param connector    connector to the system that owns task execution; modern operations are
 *                     required, while legacy list/result support is read from the connector
 * @param pageSize     default page size when a list request omits its limit
 * @param keepAlive    retention window for a terminal task's cached result; zero or negative keeps
 *                     it indefinitely
 * @param pollInterval default {@code pollInterval} suggested to requestors, or {@code null} to
 *                     suggest none when a snapshot omits one
 * @param resultPollInterval wait between {@code get} calls while awaiting a result without a
 *                     connector {@code awaitResult}, when the snapshot suggests no {@code pollInterval}
 */
public record TaskEngineSettings(
        TaskConnector connector,
        int pageSize,
        Duration keepAlive,
        @Nullable Duration pollInterval,
        @LegacyApi Duration resultPollInterval) {

    /** Default retention window for a terminal task's cached result. */
    public static final Duration DEFAULT_KEEP_ALIVE = Duration.ofMinutes(5);

    /** Default wait between {@code get} calls while awaiting a result. */
    @LegacyApi
    public static final Duration DEFAULT_RESULT_POLL_INTERVAL = Duration.ofSeconds(1);

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if {@code pageSize}, {@code pollInterval} or
     *     {@code resultPollInterval} is not positive
     */
    public TaskEngineSettings {
        Objects.requireNonNull(connector, "connector");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive, got: " + pageSize);
        }
        Objects.requireNonNull(keepAlive, "keepAlive");
        if (pollInterval != null && (pollInterval.isZero() || pollInterval.isNegative())) {
            throw new IllegalArgumentException("pollInterval must be positive, got: " + pollInterval);
        }
        Objects.requireNonNull(resultPollInterval, "resultPollInterval");
        if (resultPollInterval.isZero() || resultPollInterval.isNegative()) {
            throw new IllegalArgumentException("resultPollInterval must be positive, got: " + resultPollInterval);
        }
    }

    /**
     * Whether the connector supports the legacy (pre-SEP-2663) {@code tasks/list} operation.
     *
     * @return whether a list hook is configured
     */
    @SuppressWarnings("deprecation")
    public boolean list() {
        return connector.list() != null;
    }
}
