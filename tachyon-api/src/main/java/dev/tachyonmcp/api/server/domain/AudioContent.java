/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * An audio content block provided to or from an LLM.
 *
 * <p>The audio data is carried as raw bytes in {@code data}, with the corresponding
 * {@code mimeType} describing the encoding (e.g. {@code audio/mp3}). On the wire, {@code data}
 * is base64-encoded per the MCP protocol.
 */
@Value.Immutable
@Value.Style(allParameters = true, typeImmutable = "Default*", visibilityString = "PACKAGE")
public non-sealed interface AudioContent extends ContentBlock, HasMeta {

    /**
     * Returns the raw audio data.
     *
     * @return the audio data
     */
    @Value.Redacted
    byte[] data();

    /**
     * Returns the MIME type of the audio content.
     *
     * @return the audio MIME type
     */
    String mimeType();

    /**
     * Returns the annotations for this content, or {@code null} if none.
     *
     * @return the annotations, or {@code null}
     */
    @Nullable
    Annotations annotations();

    @Nullable
    Map<String, Object> meta();

    @Override
    default Type type() {
        return Type.AUDIO;
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
     * Creates a new builder for constructing {@code AudioContent} instances.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultAudioContent.builder();
    }

    /**
     * Creates an audio content block with no metadata or annotations.
     *
     * @param data     the audio data
     * @param mimeType the audio MIME type
     * @return a new audio content
     */
    static AudioContent of(byte[] data, String mimeType) {
        return DefaultAudioContent.of(data, mimeType, null, null);
    }

    /**
     * Creates an audio content block by reading all bytes from {@code data}, with no metadata or
     * annotations.
     *
     * @param data     the audio data stream, read fully but not closed by this method
     * @param mimeType the audio MIME type
     * @return a new audio content
     * @throws UncheckedIOException if reading {@code data} fails
     */
    static AudioContent of(InputStream data, String mimeType) {
        return of(BinaryData.readAllBytes(data), mimeType);
    }

    /**
     * Creates an audio content block with given annotations and no metadata.
     *
     * @param data        the audio data
     * @param mimeType    the audio MIME type
     * @param annotations the annotations, or {@code null}
     * @return a new audio content
     */
    static AudioContent of(byte[] data, String mimeType, @Nullable Annotations annotations) {
        return DefaultAudioContent.of(data, mimeType, annotations, null);
    }

    /**
     * Creates an audio content block with metadata and optional annotations.
     *
     * @param data        the audio data
     * @param mimeType    the audio MIME type
     * @param annotations the annotations, or {@code null}
     * @param meta        the metadata entries, or {@code null}
     * @return a new audio content
     */
    static AudioContent of(
            byte[] data, String mimeType, @Nullable Annotations annotations, @Nullable Map<String, Object> meta) {
        return DefaultAudioContent.of(data, mimeType, annotations, meta);
    }

    /**
     * Builder for {@link AudioContent}.
     */
    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy from
         * @return this builder
         */
        Builder from(AudioContent instance);

        /**
         * Sets the audio data.
         *
         * @param data the audio data
         * @return this builder
         */
        Builder data(byte[] data);

        /**
         * Sets the audio data by reading all bytes from {@code data}.
         *
         * @param data the audio data stream, read fully but not closed by this method
         * @return this builder
         * @throws UncheckedIOException if reading {@code data} fails
         */
        default Builder data(InputStream data) {
            return data(BinaryData.readAllBytes(data));
        }

        /**
         * Sets the MIME type of the audio content.
         *
         * @param mimeType the audio MIME type
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
         * Builds the {@code AudioContent} instance.
         *
         * @return a new audio content
         */
        AudioContent build();
    }
}
