/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.InternalApi;

/**
 * The one definition of what a scope may look like, for everything that names scopes: the server's
 * configuration and the scopes a tool requires. Scopes travel in a space-separated {@code scope}
 * parameter of a {@code WWW-Authenticate} challenge, so a scope that is not an RFC 6749 §3.3
 * scope-token would split in two or break the quoting.
 */
@InternalApi
public final class ScopeTokens {

    private ScopeTokens() {}

    /**
     * Checks that {@code scope} is an RFC 6749 §3.3 scope-token: {@code 1*( %x21 / %x23-5B / %x5D-7E )}.
     *
     * @param scope the scope
     * @return {@code scope}
     * @throws IllegalArgumentException if it is empty or holds a space, a control character, a quote, a
     *     backslash or a non-ASCII character
     */
    public static String require(String scope) {
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("A scope is not empty");
        }
        for (int i = 0; i < scope.length(); i++) {
            final char c = scope.charAt(i);
            if (c < 0x21 || c > 0x7e || c == '"' || c == '\\') {
                throw new IllegalArgumentException("A scope is an RFC 6749 §3.3 scope-token: " + scope);
            }
        }
        return scope;
    }
}
