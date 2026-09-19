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

    String text();

    @Nullable
    Map<String, Object> meta();

    @Nullable
    Annotations annotations();

    default ContentBlock.Type type() {
        return ContentBlock.Type.TEXT;
    }

    @Value.Check
    default void check() {
        Objects.requireNonNull(text(), "text must not be null");
    }

    static Builder builder() {
        return DefaultTextContent.builder();
    }

    /** Creates a text content block with no metadata or annotations. */
    static TextContent of(String text) {
        return DefaultTextContent.of(text, null, null);
    }

    /** Creates a text content block with given annotations and no metadata. */
    static TextContent of(String text, @Nullable Annotations annotations) {
        return DefaultTextContent.of(text, null, annotations);
    }

    /** Creates a text content block with metadata and optional annotations. */
    static TextContent of(String text, @Nullable Map<String, Object> meta, @Nullable Annotations annotations) {
        return DefaultTextContent.of(text, meta, annotations);
    }

    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(TextContent instance);

        Builder text(String text);

        Builder meta(@Nullable Map<String, ?> entries);

        Builder annotations(@Nullable Annotations annotations);

        TextContent build();
    }
}
