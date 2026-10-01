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

    /**
     * Returns the name.
     *
     * @return the name
     */
    String name();

    /**
     * Returns the title.
     *
     * @return the title
     */
    @Nullable
    String title();

    /**
     * Returns the description.
     *
     * @return the description
     */
    @Nullable
    String description();

    /**
     * Reports whether the argument is required.
     *
     * @return {@code true} if the argument is required
     */
    @Nullable
    Boolean required();

    /**
     * Validates the value invariants.
     */
    @Value.Check
    default void check() {
        if (name().isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultPromptArgument.builder();
    }

    /**
     * Creates a {@link PromptArgument} from the supplied values.
     *
     * @param name the name
     * @param title the title
     * @param description the description
     * @param required the required
     * @return the prompt argument
     */
    static PromptArgument of(
            String name, @Nullable String title, @Nullable String description, @Nullable Boolean required) {
        return DefaultPromptArgument.of(name, title, description, required);
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
        Builder from(PromptArgument instance);

        /**
         * Sets the name.
         *
         * @param name the name
         * @return this builder
         */
        Builder name(String name);

        /**
         * Sets the title.
         *
         * @param title the title
         * @return this builder
         */
        Builder title(@Nullable String title);

        /**
         * Sets the description.
         *
         * @param description the description
         * @return this builder
         */
        Builder description(@Nullable String description);

        /**
         * Sets the required.
         *
         * @param required the required
         * @return this builder
         */
        Builder required(@Nullable Boolean required);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        PromptArgument build();
    }
}
