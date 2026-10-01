/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.json;

/**
 * Pluggable payload serialization for structured values and arguments.
 *
 * <p>The primary contract is {@link String}-based because structuredContent is always parsed into
 * the protocol model by the Jackson parser before encoding — raw bytes never reach the wire — and
 * non-Jackson serdes (kotlinx.serialization, gson) emit Strings natively. {@code byte[]} round-trips
 * added conversions without saving any.
 *
 * <p>Values reaching {@link #serialize(Object)} must be types the implementation understands.
 * {@link JsonDocument} values bypass the serde.
 *
 * <p>All parameters are non-null. Passing {@code null} to
 * {@link #serialize(Object)} is undefined and may throw.
 *
 * @author Konstantin Pavlov
 */
public interface PayloadSerializer {

    /**
     * Serializes a value to its JSON string representation.
     *
     * @param <T> the value type
     * @param value the value
     * @return the encoded JSON
     */
    <T> String serialize(T value);
}
