/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Hints about tool behaviour that clients may use for safety or UX decisions.
 *
 * <p>All fields are optional — when {@code null} the client should
 * make no assumptions about the corresponding property. {@code readOnlyHint} marks
 * tools that do not modify state, {@code destructiveHint} warns about irreversible
 * changes, {@code idempotentHint} indicates safe retries, and {@code openWorldHint}
 * signals that the tool may reach outside the MCP ecosystem.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface ToolAnnotations {

    /**
     * Returns the title.
     *
     * @return the title
     */
    @Nullable
    String title();

    /**
     * Returns the read only hint.
     *
     * @return the read only hint
     */
    @Nullable
    Boolean readOnlyHint();

    /**
     * Returns the destructive hint.
     *
     * @return the destructive hint
     */
    @Nullable
    Boolean destructiveHint();

    /**
     * Returns the idempotent hint.
     *
     * @return the idempotent hint
     */
    @Nullable
    Boolean idempotentHint();

    /**
     * Returns the open world hint.
     *
     * @return the open world hint
     */
    @Nullable
    Boolean openWorldHint();

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultToolAnnotations.builder();
    }

    /**
     * Creates a {@link ToolAnnotations} from the supplied values.
     *
     * @param title the title
     * @param readOnlyHint the read only hint
     * @param destructiveHint the destructive hint
     * @param idempotentHint the idempotent hint
     * @param openWorldHint the open world hint
     * @return the tool annotations
     */
    static ToolAnnotations of(
            @Nullable String title,
            @Nullable Boolean readOnlyHint,
            @Nullable Boolean destructiveHint,
            @Nullable Boolean idempotentHint,
            @Nullable Boolean openWorldHint) {
        return DefaultToolAnnotations.of(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint);
    }

    /**
     * Builder for the enclosing type.
     */
    interface Builder {
        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(ToolAnnotations instance);

        /**
         * Sets the title.
         *
         * @param title the title
         * @return this builder
         */
        Builder title(@Nullable String title);

        /**
         * Sets the read only hint.
         *
         * @param readOnlyHint the read only hint
         * @return this builder
         */
        Builder readOnlyHint(@Nullable Boolean readOnlyHint);

        /**
         * Sets the destructive hint.
         *
         * @param destructiveHint the destructive hint
         * @return this builder
         */
        Builder destructiveHint(@Nullable Boolean destructiveHint);

        /**
         * Sets the idempotent hint.
         *
         * @param idempotentHint the idempotent hint
         * @return this builder
         */
        Builder idempotentHint(@Nullable Boolean idempotentHint);

        /**
         * Sets the open world hint.
         *
         * @param openWorldHint the open world hint
         * @return this builder
         */
        Builder openWorldHint(@Nullable Boolean openWorldHint);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        ToolAnnotations build();
    }
}
