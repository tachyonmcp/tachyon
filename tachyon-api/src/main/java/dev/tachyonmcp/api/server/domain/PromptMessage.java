/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import org.immutables.value.Value;

/**
 * A single message within a prompt, associating a {@link Role} with its {@link ContentBlock}.
 *
 * <p>Use the {@link #user(String)} or {@link #user(ContentBlock)} factories to quickly
 * construct a user-role message without specifying the role explicitly.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface PromptMessage {

    /**
     * Returns the role.
     *
     * @return the role
     */
    Role role();

    /**
     * Returns the content.
     *
     * @return the content
     */
    ContentBlock content();

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultPromptMessage.builder();
    }

    /**
     * Creates a {@link PromptMessage} from the supplied values.
     *
     * @param role the role
     * @param content the content
     * @return the prompt message
     */
    static PromptMessage of(Role role, ContentBlock content) {
        return DefaultPromptMessage.of(role, content);
    }

    /**
     * Creates a user-role message wrapping the given content block.
     *
     * @param content the content
     * @return a user-role prompt message
     */
    static PromptMessage user(ContentBlock content) {
        return DefaultPromptMessage.of(Role.USER, content);
    }

    /**
     * Creates a user-role message whose content is plain text (no annotations).
     *
     * @param text the text content
     * @return a user-role prompt message
     */
    static PromptMessage user(String text) {
        return DefaultPromptMessage.of(Role.USER, TextContent.of(text));
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
        Builder from(PromptMessage instance);

        /**
         * Sets the role.
         *
         * @param role the role
         * @return this builder
         */
        Builder role(Role role);

        /**
         * Sets the content.
         *
         * @param content the content
         * @return this builder
         */
        Builder content(ContentBlock content);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        PromptMessage build();
    }
}
