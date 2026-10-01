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
     * Returns the icons.
     *
     * @return the icons
     */
    List<Icon> icons();

    /**
     * Returns the uri.
     *
     * @return the uri
     */
    String uri();

    /**
     * Returns the description.
     *
     * @return the description
     */
    @Nullable
    String description();

    /**
     * Returns the mime type.
     *
     * @return the mime type
     */
    @Nullable
    String mimeType();

    /**
     * Returns the annotations.
     *
     * @return the annotations
     */
    @Nullable
    Annotations annotations();

    /**
     * Returns the size.
     *
     * @return the size
     */
    @Nullable
    Long size();

    @Nullable
    Map<String, Object> meta();

    /**
     * Validates the value invariants.
     */
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
     *
     * @param uri the resource URI
     * @param name the name
     * @return the resource link
     */
    static ResourceLink of(String uri, String name) {
        return builder().uri(uri).name(name).build();
    }

    /**
     * Creates a resource link with MIME type and no other optional fields.
     *
     * @param uri the resource URI
     * @param name the name
     * @param mimeType the MIME type
     * @return the resource link
     */
    static ResourceLink of(String uri, String name, @Nullable String mimeType) {
        return builder().uri(uri).name(name).mimeType(mimeType).build();
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultResourceLink.builder();
    }

    /**
     * Creates a builder for a resource link with the required fields.
     *
     * @param uri the resource URI
     * @param name the name
     * @return a new builder
     */
    static Builder builder(String uri, String name) {
        return builder().uri(uri).name(name);
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
        Builder from(ResourceLink instance);

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
         * Sets the icons.
         *
         * @param elements the elements
         * @return this builder
         */
        Builder icons(Iterable<? extends Icon> elements);

        /**
         * Sets the icons.
         *
         * @param elements the elements
         * @return this builder
         */
        default Builder icons(Icon... elements) {
            return icons(List.of(elements));
        }

        /**
         * Sets the uri.
         *
         * @param uri the resource URI
         * @return this builder
         */
        Builder uri(String uri);

        /**
         * Sets the description.
         *
         * @param description the description
         * @return this builder
         */
        Builder description(@Nullable String description);

        /**
         * Sets the mime type.
         *
         * @param mimeType the MIME type
         * @return this builder
         */
        Builder mimeType(@Nullable String mimeType);

        /**
         * Sets the annotations.
         *
         * @param annotations the annotations
         * @return this builder
         */
        Builder annotations(@Nullable Annotations annotations);

        /**
         * Sets the size.
         *
         * @param size the size
         * @return this builder
         */
        Builder size(@Nullable Long size);

        /**
         * Sets the size.
         *
         * @param size the size
         * @return this builder
         */
        default Builder size(int size) {
            return size((long) size);
        }

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
        ResourceLink build();
    }
}
