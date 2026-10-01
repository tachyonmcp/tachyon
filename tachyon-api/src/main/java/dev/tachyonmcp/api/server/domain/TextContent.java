/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.Map;
import java.util.Objects;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * A plain-text content block provided to or from an LLM.
 *
 * <p>Text is the most common content type. Optional {@link Annotations} allow the
 * server to hint at audience, priority, or modification time.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface TextContent extends ContentBlock {

    /**
     * Returns the text.
     *
     * @return the text
     */
    String text();

    @Nullable
    Map<String, Object> meta();

    /**
     * Returns the annotations.
     *
     * @return the annotations
     */
    @Nullable
    Annotations annotations();

    default ContentBlock.Type type() {
        return ContentBlock.Type.TEXT;
    }

    /**
     * Validates the value invariants.
     */
    @Value.Check
    default void check() {
        Objects.requireNonNull(text(), "text must not be null");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultTextContent.builder();
    }

    /**
     * Creates a text content block with no metadata or annotations.
     *
     * @param text the text content
     * @return the text content
     */
    static TextContent of(String text) {
        return DefaultTextContent.of(text, null, null);
    }

    /**
     * Creates a text content block with given annotations and no metadata.
     *
     * @param text the text content
     * @param annotations the annotations
     * @return the text content
     */
    static TextContent of(String text, @Nullable Annotations annotations) {
        return DefaultTextContent.of(text, null, annotations);
    }

    /**
     * Creates a text content block with metadata and optional annotations.
     *
     * @param text the text content
     * @param meta the metadata entries
     * @param annotations the annotations
     * @return the text content
     */
    static TextContent of(String text, @Nullable Map<String, Object> meta, @Nullable Annotations annotations) {
        return DefaultTextContent.of(text, meta, annotations);
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
        Builder from(TextContent instance);

        /**
         * Sets the text.
         *
         * @param text the text content
         * @return this builder
         */
        Builder text(String text);

        /**
         * Sets the meta.
         *
         * @param entries the entries
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Sets the annotations.
         *
         * @param annotations the annotations
         * @return this builder
         */
        Builder annotations(@Nullable Annotations annotations);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        TextContent build();
    }
}
