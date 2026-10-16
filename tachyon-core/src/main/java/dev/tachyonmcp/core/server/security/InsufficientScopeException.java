/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.InternalApi;
import java.util.Set;

/**
 * Raised by a request handler when the caller lacks a scope the request needs: the {@code 403}
 * counterpart of {@link AuthenticationException}. {@link SecurityRejection} turns it into a
 * transport-level {@code 403}, never a JSON-RPC error (MCP authorization § Runtime Insufficient
 * Scope). Carries no stack trace: a caller can provoke it at will.
 */
@InternalApi
public final class InsufficientScopeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Set<String> requiredScopes;

    /**
     * @param requiredScopes every scope the request requires, not only the missing ones, so the
     *     challenge can name them all in one go; immutable, in the order to report them
     */
    public InsufficientScopeException(Set<String> requiredScopes) {
        super("Insufficient scope", null, false, false);
        this.requiredScopes = requiredScopes;
    }

    /** Returns every scope the request requires. */
    public Set<String> requiredScopes() {
        return requiredScopes;
    }
}
