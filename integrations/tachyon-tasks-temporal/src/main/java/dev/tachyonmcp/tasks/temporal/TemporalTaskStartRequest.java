/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.tasks.temporal;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.server.domain.HasMeta;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** Request to start a Temporal Workflow that will be exposed as an MCP task. */
@ExperimentalApi
@Value.Immutable
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface TemporalTaskStartRequest extends HasMeta {

    /**
     * Returns the caller-assigned stable MCP task identifier.
     *
     * @return task ID
     */
    String taskId();

    /**
     * Returns the application operation name, such as an MCP tool name.
     *
     * @return operation name
     */
    String operation();

    /**
     * Returns provider-neutral JSON arguments.
     *
     * @return JSON arguments
     */
    JsonObject arguments();

    /**
     * Returns optional request metadata.
     *
     * @return metadata map, or empty if none
     */
    @Override
    @Nullable
    Map<String, Object> meta();

    /** Validates required identifiers. */
    @Value.Check
    default void check() {
        if (taskId().isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        if (operation().isBlank()) {
            throw new IllegalArgumentException("operation cannot be blank");
        }
    }

    /**
     * Creates a builder for an execution request.
     *
     * @return new builder
     */
    static Builder builder() {
        return DefaultTemporalTaskStartRequest.builder();
    }

    /** Builder for {@link TemporalTaskStartRequest}. */
    interface Builder {
        /**
         * Copies values from an existing execution request.
         *
         * @param request source request
         * @return this builder
         */
        Builder from(TemporalTaskStartRequest request);

        /**
         * Sets the caller-assigned stable task ID.
         *
         * @param taskId task ID
         * @return this builder
         */
        Builder taskId(String taskId);

        /**
         * Sets the application operation name.
         *
         * @param operation operation name
         * @return this builder
         */
        Builder operation(String operation);

        /**
         * Sets provider-neutral JSON arguments.
         *
         * @param arguments JSON arguments
         * @return this builder
         */
        Builder arguments(JsonObject arguments);

        /**
         * Sets optional request metadata.
         *
         * @param meta metadata map
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> meta);

        /**
         * Builds an immutable execution request.
         *
         * @return built request
         */
        TemporalTaskStartRequest build();
    }
}
