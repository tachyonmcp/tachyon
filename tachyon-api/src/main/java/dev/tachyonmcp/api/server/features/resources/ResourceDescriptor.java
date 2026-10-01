/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.resources;

import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.HasMeta;
import dev.tachyonmcp.api.server.domain.Icon;
import java.util.List;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Descriptor for a static (non-template) resource.
 * <p>
 * A resource is a URI-addressable piece of content such as a file, database record, or API
 * response; it also carries a human-readable {@link #name()}.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface ResourceDescriptor extends ServerFeature.Descriptor, HasMeta {

    /**
     * The URI that identifies this resource.
     *
     * @return the uri
     */
    String uri();

    /**
     * The resource's display name — a label, not an identifier. {@link #uri()} identifies the
     * resource; distinct resources MAY share a {@code name} (e.g. the same skill mounted under two
     * different namespace prefixes). See {@link Resources#register}.
     */
    String name();

    /**
     * Optional human-readable title.
     *
     * @return the title
     */
    @Nullable
    String title();

    /**
     * Optional description of this resource.
     *
     * @return the description
     */
    @Nullable
    String description();

    /**
     * Optional MIME type of the resource content.
     *
     * @return the mime type
     */
    @Nullable
    String mimeType();

    /**
     * Optional annotations for this resource.
     *
     * @return the annotations
     */
    @Nullable
    Annotations annotations();

    /**
     * Optional size of the resource in bytes.
     *
     * @return the size
     */
    @Nullable
    Long size();

    /**
     * Icons for this resource, or an empty list.
     *
     * @return the icons
     */
    List<Icon> icons();

    /**
     * Optional identifier of the extension that owns this resource.
     *
     * @return the extension id
     */
    @Nullable
    String extensionId();

    /** Optional protocol extension metadata. */
    @Nullable
    @Override
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

    /**
     * Creates a new builder for {@link ResourceDescriptor}.
     *
     * @return a new builder
     */
    static ResourceDescriptor.Builder builder() {
        return DefaultResourceDescriptor.builder();
    }

    /**
     * Creates a resource descriptor with the given fields.
     *
     * @param name the name
     * @param uri the resource URI
     * @param description the description
     * @param mimeType the MIME type
     * @return the resource descriptor
     */
    static ResourceDescriptor of(String name, String uri, @Nullable String description, @Nullable String mimeType) {
        return DefaultResourceDescriptor.builder()
                .name(name)
                .uri(uri)
                .description(description)
                .mimeType(mimeType)
                .build();
    }

    /**
     * Creates a fully specified resource descriptor.
     *
     * @param name the name
     * @param uri the resource URI
     * @param description the description
     * @param mimeType the MIME type
     * @param title the title
     * @param annotations the annotations
     * @param size the size
     * @param icons the icons
     * @return the resource descriptor
     */
    static ResourceDescriptor of(
            String name,
            String uri,
            @Nullable String description,
            @Nullable String mimeType,
            @Nullable String title,
            @Nullable Annotations annotations,
            @Nullable Long size,
            List<Icon> icons) {
        return ResourceDescriptor.builder()
                .name(name)
                .uri(uri)
                .description(description)
                .mimeType(mimeType)
                .title(title)
                .annotations(annotations)
                .size(size)
                .icons(icons)
                .build();
    }

    /** Builder for {@link ResourceDescriptor}. */
    interface Builder {

        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(ResourceDescriptor instance);

        /**
         * Sets the uri.
         *
         * @param uri the resource URI
         * @return this builder
         */
        Builder uri(String uri);

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
         * Sets the extension id.
         *
         * @param extensionId the extension identifier
         * @return this builder
         */
        Builder extensionId(@Nullable String extensionId);

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
        ResourceDescriptor build();
    }
}
