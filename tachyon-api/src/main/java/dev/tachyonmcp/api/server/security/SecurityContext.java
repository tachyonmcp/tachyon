/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.security.Principal;
import java.util.Collection;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Immutable identity of the caller of one request, as resolved by an {@link AuthenticationProvider}.
 *
 * <p>Tachyon authenticates every HTTP request on its own and passes the result explicitly, so there
 * is no thread-bound holder to propagate across executors. It never carries credentials: a bearer
 * token is verified and dropped, never handed to feature handlers.
 *
 * <p>Implement this interface to carry verified claims beyond {@link #principal()} and {@link
 * #scopes()}; handlers may then narrow {@code ctx.securityContext()} to that type.
 */
@ExperimentalApi
public interface SecurityContext {

    /**
     * Returns the context of an unauthenticated caller: no principal, no scopes.
     *
     * @return the anonymous context
     */
    static SecurityContext anonymous() {
        return AnonymousSecurityContext.INSTANCE;
    }

    /**
     * Returns the context of an authenticated caller.
     *
     * @param principal the authenticated identity
     * @param scopes the scopes the caller was granted; copied
     * @return a new authenticated context
     */
    static SecurityContext authenticated(Principal principal, Collection<String> scopes) {
        return new AuthenticatedSecurityContext(principal, Set.copyOf(scopes));
    }

    /**
     * Returns the authenticated identity, or {@code null} for an anonymous caller.
     *
     * @return the principal, or {@code null}
     */
    @Nullable
    Principal principal();

    /**
     * Returns the scopes the caller was granted, as plain OAuth scope strings.
     *
     * @return the granted scopes; empty for an anonymous caller
     */
    Set<String> scopes();

    /**
     * Returns whether the caller is authenticated, that is, whether {@link #principal()} is present.
     * Anonymous callers are never authenticated.
     *
     * @return {@code true} if a principal is present
     */
    default boolean isAuthenticated() {
        return principal() != null;
    }
}
