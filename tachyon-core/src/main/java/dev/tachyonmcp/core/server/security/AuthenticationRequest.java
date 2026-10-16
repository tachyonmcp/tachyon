/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What an {@link AuthenticationProvider} may know about a request: its method, its URI and its
 * headers, which is what a credential check needs. A read-only view of the request head, with no
 * transport type and no way to change the request; the body has not been read yet and is not here.
 *
 * <p>Valid only during the {@link AuthenticationProvider#authenticate} call it is passed to.
 */
@ExperimentalApi
public final class AuthenticationRequest {

    private final String method;
    private final String uri;
    private final Function<String, List<String>> headers;

    /**
     * Creates a view. Tachyon creates one for each request; build one yourself to test a provider.
     *
     * @param method the HTTP method, for example {@code POST}
     * @param uri the request target in origin form, as sent: the path and, if there is one, {@code ?}
     *     and the query
     * @param headers looks up a header by name: it matches the name case-insensitively and returns every
     *     value, in the order received, or an empty list or {@code null} when there is none
     */
    public AuthenticationRequest(String method, String uri, Function<String, List<String>> headers) {
        this.method = Objects.requireNonNull(method, "method");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    /**
     * Returns the HTTP method.
     *
     * @return the method, for example {@code POST}
     */
    public String method() {
        return method;
    }

    /**
     * Returns the request target in origin form, exactly as the client sent it: the path and, if there
     * is one, {@code ?} and the query. The query is not decoded.
     *
     * @return the path and query
     */
    public String uri() {
        return uri;
    }

    /**
     * Returns every value of a header, in the order the client sent them.
     *
     * @param name the header name, matched case-insensitively
     * @return the values; empty when the request has no such header; never changeable
     */
    public List<String> headers(String name) {
        final var values = headers.apply(Objects.requireNonNull(name, "name"));
        return values == null ? List.of() : Collections.unmodifiableList(values);
    }
}
