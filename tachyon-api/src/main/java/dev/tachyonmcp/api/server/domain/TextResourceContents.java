/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Text-based resource contents returned by a resource handler.
 *
 * <p>The {@code uri} identifies the originating resource, and {@code text} carries
 * the actual content. {@code mimeType} should be set when the text has a specific
 * format (e.g. {@code application/json}, {@code text/markdown}).
 */
@Value.Immutable
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface TextResourceContents extends ResourceContents {

    @Override
    @Value.Parameter(order = 1)
    String uri();

    @Override
    @Nullable
    @Value.Parameter(order = 2)
    String mimeType();

    /**
     * Returns the text.
     *
     * @return the text
     */
    @Value.Parameter(order = 3)
    String text();

    @Override
    @Nullable
    @Value.Parameter(order = 4)
    Map<String, Object> meta();

    /**
     * Validates the value invariants.
     */
    @Value.Check
    default void check() {
        if (uri().isBlank()) throw new IllegalArgumentException("uri must not be blank");
        if (text().isBlank()) throw new IllegalArgumentException("text must not be blank");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultTextResourceContents.builder();
    }

    /**
     * Creates text resource contents with no {@code _meta}.
     *
     * @param uri      the resource URI
     * @param text     the text content
     * @param mimeType the content's MIME type, or {@code null} if unspecified
     * @return the text resource contents
     */
    static TextResourceContents of(String uri, String text, @Nullable String mimeType) {
        return DefaultTextResourceContents.of(uri, mimeType, text, null);
    }

    /**
     * Creates text resource contents.
     *
     * @param uri      the resource URI
     * @param text     the text content
     * @param mimeType the content's MIME type, or {@code null} if unspecified
     * @param meta     the {@code _meta} entries, or {@code null} if none
     * @return the text resource contents
     */
    static TextResourceContents of(
            String uri, String text, @Nullable String mimeType, @Nullable Map<String, Object> meta) {
        return DefaultTextResourceContents.of(uri, mimeType, text, meta);
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
        Builder from(TextResourceContents instance);

        /**
         * Sets the uri.
         *
         * @param uri the resource URI
         * @return this builder
         */
        Builder uri(String uri);

        /**
         * Sets the text.
         *
         * @param text the text content
         * @return this builder
         */
        Builder text(String text);

        /**
         * Sets the mime type.
         *
         * @param mimeType the MIME type
         * @return this builder
         */
        Builder mimeType(@Nullable String mimeType);

        /**
         * Sets the meta.
         *
         * @param entries the entries
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        TextResourceContents build();
    }
}
