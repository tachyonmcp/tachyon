/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.config;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.time.Duration;
import org.immutables.value.Value;

/**
 * Monitoring configuration for the MCP server.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface MonitoringConfig {

    /**
     * Reports whether slow request logging is enabled.
     *
     * @return {@code true} if slow request logging is enabled
     */
    @Value.Default
    default boolean slowRequestLogging() {
        return false;
    }

    /**
     * Default slow request threshold of 10 seconds.
     *
     * @return the slow request threshold
     */
    @Value.Default
    default Duration slowRequestThreshold() {
        return Duration.ofSeconds(10);
    }

    /**
     * Default Slow request threshold.
     */
    @ExperimentalApi
    Duration DEFAULT_SLOW_REQUEST_THRESHOLD = Duration.ofSeconds(10);

    /**
     * Default.
     */
    @ExperimentalApi
    MonitoringConfig DEFAULT = DefaultMonitoringConfig.of(false, DEFAULT_SLOW_REQUEST_THRESHOLD);

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultMonitoringConfig.builder();
    }

    /** Builder for {@link MonitoringConfig}. */
    interface Builder {

        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(MonitoringConfig instance);

        /**
         * Sets whether slow request logging is enabled.
         *
         * @param slowRequestLogging the slow request logging
         * @return this builder
         */
        Builder slowRequestLogging(boolean slowRequestLogging);

        /**
         * Enables slow request logging with the default threshold.
         *
         * @return this builder
         */
        default Builder slowRequestLogging() {
            return slowRequestLogging(true);
        }

        /**
         * Sets the slow request threshold duration.
         *
         * @param slowRequestThreshold the slow request threshold
         * @return this builder
         */
        Builder slowRequestThreshold(Duration slowRequestThreshold);

        /**
         * Builds the monitoring configuration.
         *
         * @return the configured value
         */
        MonitoringConfig build();
    }
}
