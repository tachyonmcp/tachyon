/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.List;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * A reference to another resource, embedded within a content block.
 *
 * <p>Unlike {@link EmbeddedResource}, this is a lightweight pointer — it carries only
 * metadata (URI, name, title, description, MIME type) without the actual content data.
 */
@Value.Immutable
@Value.Style(allParameters = true, typeImmutable = "Default*", visibilityString = "PACKAGE")
public non-sealed interface ResourceLink extends ContentBlock {

    String name();

    @Nullable
    String title();

    List<Icon> icons();

    String uri();

    @Nullable
    String description();

    @Nullable
    String mimeType();

    @Nullable
    Annotations annotations();

    @Nullable
    Long size();

    @Nullable
    Map<String, Object> meta();

    @Value.Check
    default void check() {
        if (name().isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (uri().isBlank()) throw new IllegalArgumentException("uri must not be blank");
        Long size = size();
        if (size != null && size < 0) throw new IllegalArgumentException("size must be >= 0, got: " + size);
    }

    @Override
    default Type type() {
        return Type.RESOURCE_LINK;
    }

    /**
     * Creates a resource link with no optional fields.
     */
    static ResourceLink of(String uri, String name) {
        return builder().uri(uri).name(name).build();
    }

    /**
     * Creates a resource link with MIME type and no other optional fields.
     */
    static ResourceLink of(String uri, String name, @Nullable String mimeType) {
        return builder().uri(uri).name(name).mimeType(mimeType).build();
    }

    static Builder builder() {
        return DefaultResourceLink.builder();
    }

    /**
     * Creates a builder for a resource link with the required fields.
     */
    static Builder builder(String uri, String name) {
        return builder().uri(uri).name(name);
    }

    interface Builder {

        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ResourceLink instance);

        Builder name(String name);

        Builder title(@Nullable String title);

        Builder icons(Iterable<? extends Icon> elements);

        default Builder icons(Icon... elements) {
            return icons(List.of(elements));
        }

        Builder uri(String uri);

        Builder description(@Nullable String description);

        Builder mimeType(@Nullable String mimeType);

        Builder annotations(@Nullable Annotations annotations);

        Builder size(@Nullable Long size);

        default Builder size(int size) {
            return size((long) size);
        }

        Builder meta(@Nullable Map<String, ?> entries);

        ResourceLink build();
    }
}
