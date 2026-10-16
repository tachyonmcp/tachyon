/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.transport.netty.http.HttpHelpers;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The challenge a rejected request is answered with in {@code WWW-Authenticate} (RFC 9110 §11.6.1):
 * an authentication scheme and optional parameters, telling the client how to authenticate.
 *
 * <p>Built from a scheme and parameters, never from a header string, so a challenge is always valid
 * and a value from your own code cannot inject another header: the scheme and parameter names must be
 * HTTP tokens (RFC 9110 §5.6.2), each parameter name occurs once (§11.2), and a parameter value is
 * sent as a quoted string (§5.6.4), with {@code "} and {@code \} escaped. A value holding a character
 * that cannot be sent in a header, such as a line break, a control character or a non-ASCII character,
 * is refused with an {@link IllegalArgumentException} that does not repeat it.
 *
 * <pre>{@code
 * AuthenticationChallenge.of("ApiKey").withParam("realm", "orders")  // ApiKey realm="orders"
 * }</pre>
 *
 * <p>Immutable. For a rejected OAuth 2.1 bearer token use {@link
 * AuthenticationException#bearerInvalidToken} and {@link AuthenticationException#bearerInvalidRequest}.
 */
@ExperimentalApi
public final class AuthenticationChallenge {

    private final String scheme;
    private final Map<String, String> params;

    private AuthenticationChallenge(String scheme, Map<String, String> params) {
        this.scheme = scheme;
        this.params = params;
    }

    /**
     * Creates a challenge with no parameters.
     *
     * @param scheme the authentication scheme, for example {@code ApiKey}; an HTTP token
     * @return the challenge
     * @throws IllegalArgumentException if {@code scheme} is not an HTTP token
     */
    public static AuthenticationChallenge of(String scheme) {
        requireToken(scheme, "scheme");
        return new AuthenticationChallenge(scheme, Map.of());
    }

    /**
     * Returns this challenge with one more parameter. This challenge is left as it was.
     *
     * @param name the parameter name, for example {@code realm}; an HTTP token not used yet in this
     *     challenge, whatever its case
     * @param value the parameter value, printable ASCII or tab; it is quoted and escaped for you
     * @return a new challenge
     * @throws IllegalArgumentException if {@code name} is not an HTTP token or repeats a name, or if
     *     {@code value} cannot be sent in a header
     */
    public AuthenticationChallenge withParam(String name, String value) {
        requireToken(name, "parameter name");
        for (final var existing : params.keySet()) {
            if (existing.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("A challenge parameter name occurs once");
            }
        }
        requireQuotable(value);
        final var more = new LinkedHashMap<>(params);
        more.put(name, value);
        return new AuthenticationChallenge(scheme, Collections.unmodifiableMap(more));
    }

    /**
     * Returns the {@code WWW-Authenticate} field value: the scheme, then its parameters as {@code
     * name="value"} separated by commas.
     *
     * @return the header value
     */
    public String headerValue() {
        if (params.isEmpty()) {
            return scheme;
        }
        final var header = new StringBuilder(scheme);
        var separator = " ";
        for (final var param : params.entrySet()) {
            header.append(separator).append(param.getKey()).append("=\"");
            for (final char c : param.getValue().toCharArray()) {
                if (c == '"' || c == '\\') {
                    header.append('\\');
                }
                header.append(c);
            }
            header.append('"');
            separator = ", ";
        }
        return header.toString();
    }

    private static void requireToken(String text, String what) {
        Objects.requireNonNull(text, what);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A challenge " + what + " is not empty");
        }
        if (!HttpHelpers.isToken(text)) {
            throw new IllegalArgumentException("A challenge " + what + " is an HTTP token");
        }
    }

    private static void requireQuotable(String value) {
        Objects.requireNonNull(value, "value");
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7e)) {
                throw new IllegalArgumentException(
                        "A challenge parameter value holds a character that cannot be sent in a header");
            }
        }
    }
}
