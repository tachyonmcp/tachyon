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

    /** The URI that identifies this resource. */
    String uri();

    /**
     * The resource's display name — a label, not an identifier. {@link #uri()} identifies the
     * resource; distinct resources MAY share a {@code name} (e.g. the same skill mounted under two
     * different namespace prefixes). See {@link Resources#register}.
     */
    String name();

    /** Optional human-readable title. */
    @Nullable
    String title();

    /** Optional description of this resource. */
    @Nullable
    String description();

    /** Optional MIME type of the resource content. */
    @Nullable
    String mimeType();

    /** Optional annotations for this resource. */
    @Nullable
    Annotations annotations();

    /** Optional size of the resource in bytes. */
    @Nullable
    Long size();

    /** Icons for this resource, or an empty list. */
    List<Icon> icons();

    /** Optional identifier of the extension that owns this resource. */
    @Nullable
    String extensionId();

    /** Optional protocol extension metadata. */
    @Nullable
    @Override
    Map<String, Object> meta();

    @Value.Check
    default void check() {
        if (name().isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (uri().isBlank()) throw new IllegalArgumentException("uri must not be blank");
        Long size = size();
        if (size != null && size < 0) throw new IllegalArgumentException("size must be >= 0, got: " + size);
    }

    /** Creates a new builder for {@link ResourceDescriptor}. */
    static ResourceDescriptor.Builder builder() {
        return DefaultResourceDescriptor.builder();
    }

    /** Creates a resource descriptor with the given fields. */
    static ResourceDescriptor of(String name, String uri, @Nullable String description, @Nullable String mimeType) {
        return DefaultResourceDescriptor.builder()
                .name(name)
                .uri(uri)
                .description(description)
                .mimeType(mimeType)
                .build();
    }

    /** Creates a fully specified resource descriptor. */
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

        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ResourceDescriptor instance);

        Builder uri(String uri);

        Builder name(String name);

        Builder title(@Nullable String title);

        Builder description(@Nullable String description);

        Builder mimeType(@Nullable String mimeType);

        Builder annotations(@Nullable Annotations annotations);

        Builder size(@Nullable Long size);

        default Builder size(int size) {
            return size((long) size);
        }

        Builder icons(Iterable<? extends Icon> elements);

        default Builder icons(Icon... elements) {
            return icons(List.of(elements));
        }

        Builder extensionId(@Nullable String extensionId);

        Builder meta(@Nullable Map<String, ?> entries);

        ResourceDescriptor build();
    }
}
