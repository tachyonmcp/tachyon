/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Describes a single argument accepted by a prompt template.
 *
 * <p>Arguments are matched by {@code name}. The {@code required} flag tells the client
 * whether the argument must be provided; when absent or {@code null}, the argument is
 * considered optional.
 */
@Value.Immutable
@Value.Style(allParameters = true, typeImmutable = "Default*", visibilityString = "PACKAGE")
public interface PromptArgument {

    String name();

    @Nullable
    String title();

    @Nullable
    String description();

    @Nullable
    Boolean required();

    @Value.Check
    default void check() {
        if (name().isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    static Builder builder() {
        return DefaultPromptArgument.builder();
    }

    static PromptArgument of(
            String name, @Nullable String title, @Nullable String description, @Nullable Boolean required) {
        return DefaultPromptArgument.of(name, title, description, required);
    }

    interface Builder {

        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(PromptArgument instance);

        Builder name(String name);

        Builder title(@Nullable String title);

        Builder description(@Nullable String description);

        Builder required(@Nullable Boolean required);

        PromptArgument build();
    }
}
