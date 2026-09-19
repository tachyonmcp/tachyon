/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.LegacyApi;
import dev.tachyonmcp.api.server.domain.HasMeta;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** Legacy request to list authoritative task projections. */
@ExperimentalApi
@LegacyApi
@Value.Immutable
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface TaskListRequest extends HasMeta {

    /** Returns the resolved positive page size. */
    int limit();

    /** Returns the pagination cursor, or {@code null} for the first page. */
    @Nullable
    String cursor();

    /** Returns request metadata, or {@code null} when absent. */
    @Override
    @Nullable
    Map<String, Object> meta();

    /** Validates the resolved page size. */
    @Value.Check
    default void check() {
        if (limit() <= 0) throw new IllegalArgumentException("limit must be positive: " + limit());
    }

    /** Creates a request builder. */
    static Builder builder() {
        return DefaultTaskListRequest.builder();
    }

    /** Builder for {@link TaskListRequest}. */
    interface Builder {
        /** Copies an existing request. */
        Builder from(TaskListRequest request);

        /** Sets the resolved page size. */
        Builder limit(int limit);

        /** Sets the pagination cursor. */
        Builder cursor(@Nullable String cursor);

        /** Sets request metadata. */
        Builder meta(@Nullable Map<String, ?> meta);

        /** Builds the request. */
        TaskListRequest build();
    }
}
