/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Binary resource contents.
 *
 * <p>Used when a resource cannot be represented as text. The {@code uri} identifies the
 * resource, {@code mimeType} describes the binary format, and {@code blob} carries the raw
 * binary data. On the wire, {@code blob} is base64-encoded per the MCP protocol.
 */
@Value.Immutable
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface BlobResourceContents extends ResourceContents {

    @Override
    @Value.Parameter(order = 1)
    String uri();

    @Override
    @Nullable
    @Value.Parameter(order = 2)
    String mimeType();

    /**
     * Returns the raw binary content.
     *
     * @return the blob data
     */
    @Value.Parameter(order = 3)
    @Value.Redacted
    byte[] blob();

    @Override
    @Nullable
    @Value.Parameter(order = 4)
    Map<String, Object> meta();

    /**
     * Validates that required fields are not blank.
     *
     * @throws IllegalArgumentException if {@code uri} is blank
     */
    @Value.Check
    default void check() {
        if (uri().isBlank()) throw new IllegalArgumentException("uri must not be blank");
    }

    /**
     * Creates binary resource contents with no {@code _meta}.
     *
     * @param uri      the resource URI
     * @param blob     the binary content
     * @param mimeType the content's MIME type, or {@code null} if unspecified
     * @return a new blob resource contents
     */
    static BlobResourceContents of(String uri, byte[] blob, @Nullable String mimeType) {
        return DefaultBlobResourceContents.of(uri, mimeType, blob, null);
    }

    /**
     * Creates binary resource contents.
     *
     * @param uri      the resource URI
     * @param blob     the binary content
     * @param mimeType the content's MIME type, or {@code null} if unspecified
     * @param meta     the {@code _meta} entries, or {@code null} if none
     * @return a new blob resource contents
     */
    static BlobResourceContents of(
            String uri, byte[] blob, @Nullable String mimeType, @Nullable Map<String, Object> meta) {
        return DefaultBlobResourceContents.of(uri, mimeType, blob, meta);
    }

    /**
     * Creates binary resource contents by reading all bytes from {@code blob}, with no
     * {@code _meta}.
     *
     * @param uri      the resource URI
     * @param blob     the binary content stream, read fully but not closed by this method
     * @param mimeType the content's MIME type, or {@code null} if unspecified
     * @return a new blob resource contents
     * @throws UncheckedIOException if reading {@code blob} fails
     */
    static BlobResourceContents of(String uri, InputStream blob, @Nullable String mimeType) {
        return of(uri, BinaryData.readAllBytes(blob), mimeType);
    }

    /**
     * Creates a new builder for constructing {@code BlobResourceContents} instances.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultBlobResourceContents.builder();
    }

    /**
     * Builder for {@link BlobResourceContents}.
     */
    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy from
         * @return this builder
         */
        Builder from(BlobResourceContents instance);

        /**
         * Sets the resource URI.
         *
         * @param uri the resource URI
         * @return this builder
         */
        Builder uri(String uri);

        /**
         * Sets the binary content.
         *
         * @param blob the blob data
         * @return this builder
         */
        Builder blob(byte[] blob);

        /**
         * Sets the binary content by reading all bytes from {@code blob}.
         *
         * @param blob the blob data stream, read fully but not closed by this method
         * @return this builder
         * @throws UncheckedIOException if reading {@code blob} fails
         */
        default Builder blob(InputStream blob) {
            return blob(BinaryData.readAllBytes(blob));
        }

        /**
         * Sets the MIME type of the content.
         *
         * @param mimeType the MIME type, or {@code null}
         * @return this builder
         */
        Builder mimeType(@Nullable String mimeType);

        /**
         * Sets the metadata entries.
         *
         * @param entries the metadata map, or {@code null}
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Builds the {@code BlobResourceContents} instance.
         *
         * @return a new blob resource contents
         */
        BlobResourceContents build();
    }
}
