/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.domain.HasMeta;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** Client input submitted to an externally executed task for {@code tasks/update}. */
@ExperimentalApi
@Value.Immutable
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface TaskUpdateRequest extends HasMeta {

    /** Returns the task being updated. */
    String taskId();

    /** Returns input values keyed by request name. */
    Map<String, Object> inputResponses();

    /** Returns request metadata, or {@code null} when absent. */
    @Override
    @Nullable
    Map<String, Object> meta();

    /** Validates task identity. */
    @Value.Check
    default void check() {
        if (taskId().isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
    }

    /** Creates a builder for a task update request. */
    static Builder builder() {
        return DefaultTaskUpdateRequest.builder();
    }

    /** Builder for {@link TaskUpdateRequest}. */
    interface Builder {
        /** Copies values from an existing update request. */
        Builder from(TaskUpdateRequest request);

        /** Sets the task being updated. */
        Builder taskId(String taskId);

        /** Sets input values keyed by request name. */
        Builder inputResponses(Map<String, ?> inputResponses);

        /** Sets request metadata. */
        Builder meta(@Nullable Map<String, ?> meta);

        /** Builds an immutable task update request. */
        TaskUpdateRequest build();
    }
}
