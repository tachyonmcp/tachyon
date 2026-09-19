/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * An image provided to or from an LLM.
 *
 * <p>The image data is carried as raw bytes in {@code data}, with the corresponding
 * {@code mimeType} describing the format (e.g. {@code image/png}, {@code image/jpeg}). On the
 * wire, {@code data} is base64-encoded per the MCP protocol.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface ImageContent extends ContentBlock {

    /**
     * Returns the raw image data.
     *
     * @return the image data
     */
    @Value.Redacted
    byte[] data();

    /**
     * Returns the MIME type of the image content.
     *
     * @return the image MIME type
     */
    String mimeType();

    /**
     * Returns the annotations for this content, or {@code null} if none.
     *
     * @return the annotations, or {@code null}
     */
    @Nullable
    Annotations annotations();

    /**
     * Returns the request-level metadata for this content, or {@code null} if none.
     *
     * @return the metadata entries, or {@code null}
     */
    @Nullable
    Map<String, Object> meta();

    @Override
    default Type type() {
        return Type.IMAGE;
    }

    /**
     * Validates that required fields are not blank.
     *
     * @throws IllegalArgumentException if {@code data} is empty or {@code mimeType} is blank
     */
    @Value.Check
    default void check() {
        if (data().length == 0) throw new IllegalArgumentException("data must not be empty");
        if (mimeType().isBlank()) throw new IllegalArgumentException("mimeType must not be blank");
    }

    /**
     * Creates a new builder for constructing {@code ImageContent} instances.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultImageContent.builder();
    }

    /**
     * Creates an image content block, with no metadata or annotations.
     *
     * @param data     the image data
     * @param mimeType the image MIME type (e.g. {@code image/png})
     * @return a new image content
     */
    static ImageContent of(byte[] data, String mimeType) {
        return DefaultImageContent.of(data, mimeType, null, null);
    }

    /**
     * Creates an image content block by reading all bytes from {@code data}, with no metadata or
     * annotations.
     *
     * @param data     the image data stream, read fully but not closed by this method
     * @param mimeType the image MIME type (e.g. {@code image/png})
     * @throws UncheckedIOException if reading {@code data} fails
     * @return a new image content
     */
    static ImageContent of(InputStream data, String mimeType) {
        return of(BinaryData.readAllBytes(data), mimeType);
    }

    /** Creates an image content block with given annotations and no metadata. */
    static ImageContent of(byte[] data, String mimeType, @Nullable Annotations annotations) {
        return DefaultImageContent.of(data, mimeType, annotations, null);
    }

    /** Creates an image content block with metadata and optional annotations. */
    static ImageContent of(
            byte[] data, String mimeType, @Nullable Annotations annotations, @Nullable Map<String, Object> meta) {
        return DefaultImageContent.of(data, mimeType, annotations, meta);
    }

    /**
     * Builder for {@link ImageContent}.
     */
    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ImageContent instance);

        /**
         * Sets the image data.
         *
         * @param data the image data
         * @return this builder
         */
        Builder data(byte[] data);

        /**
         * Sets the image data by reading all bytes from {@code data}.
         *
         * @param data the image data stream, read fully but not closed by this method
         * @return this builder
         * @throws UncheckedIOException if reading {@code data} fails
         */
        default Builder data(InputStream data) {
            return data(BinaryData.readAllBytes(data));
        }

        /**
         * Sets the MIME type of the image content.
         *
         * @param mimeType the image MIME type
         * @return this builder
         */
        Builder mimeType(String mimeType);

        /**
         * Sets the annotations.
         *
         * @param annotations the annotations, or {@code null}
         * @return this builder
         */
        Builder annotations(@Nullable Annotations annotations);

        /**
         * Sets the metadata entries.
         *
         * @param entries the metadata map, or {@code null}
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Builds the {@code ImageContent} instance.
         *
         * @return a new image content
         */
        ImageContent build();
    }
}
