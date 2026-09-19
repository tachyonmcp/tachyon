/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.domain.HasMeta;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** Request to retrieve the authoritative state of one task. */
@ExperimentalApi
@Value.Immutable
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface TaskGetRequest extends HasMeta {

    /** Returns the task identifier. */
    String taskId();

    /** Returns request metadata, or {@code null} when absent. */
    @Override
    @Nullable
    Map<String, Object> meta();

    /** Validates the task identifier. */
    @Value.Check
    default void check() {
        if (taskId().isBlank()) throw new IllegalArgumentException("taskId cannot be blank");
    }

    /** Creates a request builder. */
    static Builder builder() {
        return DefaultTaskGetRequest.builder();
    }

    /** Builder for {@link TaskGetRequest}. */
    interface Builder {
        /** Copies an existing request. */
        Builder from(TaskGetRequest request);

        /** Sets the task identifier. */
        Builder taskId(String taskId);

        /** Sets request metadata. */
        Builder meta(@Nullable Map<String, ?> meta);

        /** Builds the request. */
        TaskGetRequest build();
    }
}
