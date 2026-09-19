/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * A complete resource embedded inline within a tool result, prompt, or other content.
 *
 * <p>The embedded {@code resource} contains both the URI and the actual content
 * ({@link TextResourceContents} or {@link BlobResourceContents}), allowing the
 * server to attach resource data directly without requiring a separate read round-trip.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface EmbeddedResource extends ContentBlock, HasMeta {

    ResourceContents resource();

    /**
     * Returns the annotations for this content, or {@code null} if none.
     *
     * @return the annotations, or {@code null}
     */
    @Nullable
    Annotations annotations();

    /**
     * Returns optional protocol extension metadata.
     *
     * @return the metadata entries, or {@code null}
     */
    @Nullable
    Map<String, Object> meta();

    @Override
    default Type type() {
        return Type.RESOURCE;
    }

    static Builder builder() {
        return DefaultEmbeddedResource.builder();
    }

    /**
     * Creates an embedded resource with no metadata or annotations.
     *
     * @param resource the resource contents
     * @return a new embedded resource
     */
    static EmbeddedResource of(ResourceContents resource) {
        return DefaultEmbeddedResource.of(resource, null, null);
    }

    /**
     * Creates an embedded resource with given annotations and no metadata.
     *
     * @param resource    the resource contents
     * @param annotations the annotations, or {@code null}
     * @return a new embedded resource
     */
    static EmbeddedResource of(ResourceContents resource, @Nullable Annotations annotations) {
        return DefaultEmbeddedResource.of(resource, annotations, null);
    }

    /**
     * Creates an embedded resource with metadata and optional annotations.
     *
     * @param resource    the resource contents
     * @param annotations the annotations, or {@code null}
     * @param meta        the metadata entries, or {@code null}
     * @return a new embedded resource
     */
    static EmbeddedResource of(
            ResourceContents resource, @Nullable Annotations annotations, @Nullable Map<String, Object> meta) {
        return DefaultEmbeddedResource.of(resource, annotations, meta);
    }

    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy from
         * @return this builder
         */
        Builder from(EmbeddedResource instance);

        Builder resource(ResourceContents resource);

        Builder annotations(@Nullable Annotations annotations);

        Builder meta(@Nullable Map<String, ?> entries);

        EmbeddedResource build();
    }
}
