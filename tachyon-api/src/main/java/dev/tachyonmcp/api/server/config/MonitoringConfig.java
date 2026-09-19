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

    @Value.Default
    default boolean slowRequestLogging() {
        return false;
    }

    /** Default slow request threshold of 10 seconds. */
    @Value.Default
    default Duration slowRequestThreshold() {
        return Duration.ofSeconds(10);
    }

    @ExperimentalApi
    Duration DEFAULT_SLOW_REQUEST_THRESHOLD = Duration.ofSeconds(10);

    @ExperimentalApi
    MonitoringConfig DEFAULT = DefaultMonitoringConfig.of(false, DEFAULT_SLOW_REQUEST_THRESHOLD);

    static Builder builder() {
        return DefaultMonitoringConfig.builder();
    }

    /** Builder for {@link MonitoringConfig}. */
    interface Builder {

        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(MonitoringConfig instance);

        /** Sets whether slow request logging is enabled. */
        Builder slowRequestLogging(boolean slowRequestLogging);

        /** Enables slow request logging with the default threshold. */
        default Builder slowRequestLogging() {
            return slowRequestLogging(true);
        }

        /** Sets the slow request threshold duration. */
        Builder slowRequestThreshold(Duration slowRequestThreshold);

        /** Builds the monitoring configuration. */
        MonitoringConfig build();
    }
}
