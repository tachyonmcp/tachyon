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

    @Value.Parameter(order = 3)
    String text();

    @Override
    @Nullable
    @Value.Parameter(order = 4)
    Map<String, Object> meta();

    @Value.Check
    default void check() {
        if (uri().isBlank()) throw new IllegalArgumentException("uri must not be blank");
        if (text().isBlank()) throw new IllegalArgumentException("text must not be blank");
    }

    static Builder builder() {
        return DefaultTextResourceContents.builder();
    }

    /**
     * Creates text resource contents with no {@code _meta}.
     *
     * @param uri      the resource URI
     * @param text     the text content
     * @param mimeType the content's MIME type, or {@code null} if unspecified
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
     */
    static TextResourceContents of(
            String uri, String text, @Nullable String mimeType, @Nullable Map<String, Object> meta) {
        return DefaultTextResourceContents.of(uri, mimeType, text, meta);
    }

    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(TextResourceContents instance);

        Builder uri(String uri);

        Builder text(String text);

        Builder mimeType(@Nullable String mimeType);

        Builder meta(@Nullable Map<String, ?> entries);

        TextResourceContents build();
    }
}
