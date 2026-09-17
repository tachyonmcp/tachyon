/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp;

import dev.tachyonmcp.api.annotations.InternalApi;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * The SEP-2243 mirror-header wire format: a value travels either literally or wrapped in the
 * {@code =?base64?<payload>?=} sentinel that carries text an HTTP field value cannot hold verbatim.
 * The sentinel markers are case-sensitive and must appear exactly as shown, so a value wrapped in
 * {@code =?BASE64?...?=}, or missing either marker, is a literal.
 *
 * <p>Both entry points unwrap that same format and reject the same malformed payload, differing
 * only in how they report it, because the two headers give it different meanings. Both also strip
 * RFC 9110 optional whitespace, which no Netty-decoded request can actually carry — the decoder
 * trims a field value and {@code DefaultHttpHeaders#set} rejects one padded programmatically — so
 * the strip is there for a non-Netty caller, not for the wire. SEP-2243 lists
 * {@code Mcp-Name:  foo } against body {@code foo} as an accept, and a value whose own whitespace is
 * significant must be Base64-wrapped.
 */
@InternalApi
public final class McpHeaderValue {

    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    private McpHeaderValue() {}

    /**
     * Decodes a mirror whose only use is comparison against a body value ({@code Mcp-Name}). A
     * payload that does not decode yields {@code null}, which the caller reports as an ordinary
     * mismatch: the value it would have carried is unknowable, so there is nothing it could
     * legitimately match, and SEP-2243 gives a failed decode and a mismatch the same error code.
     *
     * @param raw the raw header value, or {@code null} when the header is absent
     * @return the decoded value, or {@code null} if absent or undecodable
     */
    public static @Nullable String decodeLenient(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return decodeStrict(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Decodes an {@code Mcp-Param-*} value, where malformed Base64 is its own rejection reason
     * (SEP-2243: "Server MUST reject requests with invalid Base64 padding or characters") rather
     * than degrading into a plain value mismatch.
     *
     * @param raw the raw header value
     * @return the decoded value
     * @throws IllegalArgumentException if the sentinel wraps a payload that is not valid, fully
     *     padded Base64
     */
    public static String decodeStrict(String raw) {
        var trimmed = raw.strip();
        var payload = base64Payload(trimmed);
        return payload == null ? trimmed : decodeBase64(payload);
    }

    /** The encoded payload inside the sentinel wrapper, or {@code null} for a literal value. */
    private static @Nullable String base64Payload(String value) {
        if (value.length() < BASE64_PREFIX.length() + BASE64_SUFFIX.length()
                || !value.startsWith(BASE64_PREFIX)
                || !value.endsWith(BASE64_SUFFIX)) {
            return null;
        }
        return value.substring(BASE64_PREFIX.length(), value.length() - BASE64_SUFFIX.length());
    }

    private static String decodeBase64(String payload) {
        // Base64.getDecoder() tolerates missing padding (e.g. "SGVsbG8" for "Hello"), but SEP-2243
        // requires rejecting malformed padding, not just invalid alphabet characters.
        if (payload.length() % 4 != 0) {
            throw new IllegalArgumentException("invalid Base64 padding");
        }
        return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
    }
}
