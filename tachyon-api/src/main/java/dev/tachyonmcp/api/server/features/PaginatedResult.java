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
     *
     * @return the items
     */
    List<R> items();

    /**
     * Cursor for the next page, or {@code null} if this is the last page.
     *
     * @return the next cursor
     */
    @Nullable
    String nextCursor();

    /**
     * Returns {@code true} if there are more items beyond this page.
     *
     * @return {@code true} if there are more items beyond this page
     */
    default boolean hasMore() {
        return nextCursor() != null;
    }

    /**
     * Returns {@code true} if the requested cursor was valid: either absent (first page) or found
     * among the underlying items. {@code false} means a non-null cursor matched nothing, and per
     * the MCP pagination spec the caller SHOULD raise -32602 (Invalid params).
     *
     * @return {@code true} if the requested cursor was valid: either absent (first page) or found among the underlying items
     */
    boolean cursorValid();

    /**
     * Creates a new builder.
     *
     * @param <R> the value type
     * @return a new builder
     */
    static <R> Builder<R> builder() {
        return DefaultPaginatedResult.builder();
    }

    /**
     * Creates a {@link PaginatedResult} from the supplied values.
     *
     * @param items the items
     * @param nextCursor the next cursor
     * @param cursorValid the cursor valid
     * @param <R> the value type
     * @return the paginated result
     */
    static <R> PaginatedResult<R> of(List<R> items, @Nullable String nextCursor, boolean cursorValid) {
        return DefaultPaginatedResult.of(items, nextCursor, cursorValid);
    }

    /**
     * Builder for the enclosing type.
     *
     * @param <R> the value type
     */
    interface Builder<R> {
        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder<R> from(PaginatedResult<R> instance);

        /**
         * Sets the items.
         *
         * @param elements the elements
         * @return this builder
         */
        Builder<R> items(Iterable<? extends R> elements);

        /**
         * Sets the next cursor.
         *
         * @param nextCursor the next cursor
         * @return this builder
         */
        Builder<R> nextCursor(@Nullable String nextCursor);

        /**
         * Sets the cursor validity flag.
         *
         * @param cursorValid the cursor valid
         * @return this builder
         */
        Builder<R> cursorValid(boolean cursorValid);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        PaginatedResult<R> build();
    }
}
