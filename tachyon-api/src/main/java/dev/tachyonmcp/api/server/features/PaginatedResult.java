/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features;

import dev.tachyonmcp.api.annotations.InternalApi;
import java.util.List;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * A paginated result with an optional cursor for the next page.
 *
 * @param <R> the item type
 */
@InternalApi
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface PaginatedResult<R> {

    /**
     * The items on this page.
     */
    List<R> items();

    /**
     * Cursor for the next page, or {@code null} if this is the last page.
     */
    @Nullable
    String nextCursor();

    /**
     * Returns {@code true} if there are more items beyond this page.
     */
    default boolean hasMore() {
        return nextCursor() != null;
    }

    /**
     * Returns {@code true} if the requested cursor was valid: either absent (first page) or found
     * among the underlying items. {@code false} means a non-null cursor matched nothing, and per
     * the MCP pagination spec the caller SHOULD raise -32602 (Invalid params).
     */
    boolean cursorValid();

    static <R> Builder<R> builder() {
        return DefaultPaginatedResult.builder();
    }

    static <R> PaginatedResult<R> of(List<R> items, @Nullable String nextCursor, boolean cursorValid) {
        return DefaultPaginatedResult.of(items, nextCursor, cursorValid);
    }

    interface Builder<R> {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder<R> from(PaginatedResult<R> instance);

        Builder<R> items(Iterable<? extends R> elements);

        Builder<R> nextCursor(@Nullable String nextCursor);

        Builder<R> cursorValid(boolean cursorValid);

        PaginatedResult<R> build();
    }
}
