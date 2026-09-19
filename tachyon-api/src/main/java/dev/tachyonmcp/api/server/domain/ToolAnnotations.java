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

    @Nullable
    String title();

    @Nullable
    Boolean readOnlyHint();

    @Nullable
    Boolean destructiveHint();

    @Nullable
    Boolean idempotentHint();

    @Nullable
    Boolean openWorldHint();

    static Builder builder() {
        return DefaultToolAnnotations.builder();
    }

    static ToolAnnotations of(
            @Nullable String title,
            @Nullable Boolean readOnlyHint,
            @Nullable Boolean destructiveHint,
            @Nullable Boolean idempotentHint,
            @Nullable Boolean openWorldHint) {
        return DefaultToolAnnotations.of(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint);
    }

    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ToolAnnotations instance);

        Builder title(@Nullable String title);

        Builder readOnlyHint(@Nullable Boolean readOnlyHint);

        Builder destructiveHint(@Nullable Boolean destructiveHint);

        Builder idempotentHint(@Nullable Boolean idempotentHint);

        Builder openWorldHint(@Nullable Boolean openWorldHint);

        ToolAnnotations build();
    }
}
