/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.resources;

import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.HasMeta;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.UriTemplate;
import java.util.List;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Descriptor for a resource URI template.
 * <p>
 * A template describes a family of resources matching a URI pattern (e.g.
 * {@code file:///{path}}). Each concrete URI within the template is resolved
 * at request time via a {@link ResourceRequest}.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface ResourceTemplateDescriptor extends ServerFeature.Descriptor, HasMeta {

    /** The template name, unique within the server. */
    @Override
    String name();

    /**
     * The URI template pattern (e.g. {@code file:///{path}}).
     *
     * @return the uri template
     */
    String uriTemplate();

    /**
     * Optional description of the resource family.
     *
     * @return the description
     */
    @Nullable
    String description();

    /**
     * Optional MIME type that all matching resources share.
     *
     * @return the mime type
     */
    @Nullable
    String mimeType();

    /**
     * Optional human-readable title.
     *
     * @return the title
     */
    @Nullable
    String title();

    /**
     * Optional annotations shared by resources matching this template.
     *
     * @return the annotations
     */
    @Nullable
    Annotations annotations();

    /**
     * Optional identifier of the extension that owns this template.
     *
     * @return the extension id
     */
    @Nullable
    String extensionId();

    /**
     * Icons for resources matching this template, or an empty list.
     *
     * @return the icons
     */
    List<Icon> icons();

    /** Optional protocol extension metadata. */
    @Nullable
    @Override
    Map<String, Object> meta();

    /**
     * Validates the resource template descriptor's name and URI template.
     *
     * @throws IllegalArgumentException if the name or URI template is blank or invalid
     */
    @Value.Check
    default void check() {
        if (name().isBlank()) throw new IllegalArgumentException("name must not be blank");
        final var uriTemplate = uriTemplate();
        if (uriTemplate == null || uriTemplate.isBlank())
            throw new IllegalArgumentException("uriTemplate must not be null or blank");
        UriTemplate.create(uriTemplate);
    }

    /**
     * Creates a builder for constructing resource template descriptors.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultResourceTemplateDescriptor.builder();
    }

    /** Builder for {@link ResourceTemplateDescriptor}. */
    interface Builder {

        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(ResourceTemplateDescriptor instance);

        /**
         * Sets the template name, unique within the server.
         *
         * @param name the name
         * @return this builder
         */
        Builder name(String name);

        /**
         * Sets the URI template pattern (e.g. {@code file:///{path}}).
         *
         * @param uriTemplate the URI template
         * @return this builder
         */
        Builder uriTemplate(String uriTemplate);

        /**
         * Sets the optional description of the resource family.
         *
         * @param description the description
         * @return this builder
         */
        Builder description(@Nullable String description);

        /**
         * Sets the optional MIME type that all matching resources share.
         *
         * @param mimeType the MIME type
         * @return this builder
         */
        Builder mimeType(@Nullable String mimeType);

        /**
         * Sets the optional human-readable title.
         *
         * @param title the title
         * @return this builder
         */
        Builder title(@Nullable String title);

        /**
         * Sets the optional annotations shared by resources matching this template.
         *
         * @param annotations the annotations
         * @return this builder
         */
        Builder annotations(@Nullable Annotations annotations);

        /**
         * Sets the icons for resources matching this template.
         *
         * @param elements the elements
         * @return this builder
         */
        Builder icons(Iterable<? extends Icon> elements);

        /**
         * Sets the icons for resources matching this template; an empty array means no icons.
         *
         * @param elements the elements
         * @return this builder
         */
        default Builder icons(Icon... elements) {
            return icons(List.of(elements));
        }

        /**
         * Sets the optional identifier of the extension that owns this template.
         *
         * @param extensionId the extension identifier
         * @return this builder
         */
        Builder extensionId(@Nullable String extensionId);

        /**
         * Sets optional protocol extension metadata.
         *
         * @param entries metadata entries, or {@code null} for none
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Builds the {@link ResourceTemplateDescriptor}.
         *
         * @return the configured value
         */
        ResourceTemplateDescriptor build();
    }
}
