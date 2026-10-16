/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.security.Principal;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Immutable identity of the caller of one request, as resolved by the server's authentication.
 *
 * <p>Tachyon authenticates every HTTP request on its own and passes the result explicitly, so there
 * is no thread-bound holder to propagate across executors. It never carries credentials: a bearer
 * token is verified and dropped, never handed to feature handlers.
 *
 * <p>A context is either anonymous or authenticated, nothing in between, so this interface is sealed.
 * To carry verified claims beyond {@link #principal()} and {@link #scopes()}, give the {@link
 * Principal} you authenticate with the claims and narrow {@code ctx.securityContext().principal()} to
 * your type in handlers.
 */
@ExperimentalApi
public sealed interface SecurityContext permits AnonymousSecurityContext, AuthenticatedSecurityContext {

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
        return new AuthenticatedSecurityContext(principal, Set.copyOf(scopes), null);
    }

    /**
     * Returns the context of an authenticated caller whose credentials expire. Tachyon closes the SSE
     * streams this caller's request opened at {@code expiresAt}.
     *
     * @param principal the authenticated identity
     * @param scopes the scopes the caller was granted; copied
     * @param expiresAt when the credentials stop being valid
     * @return a new authenticated context
     */
    static SecurityContext authenticated(Principal principal, Collection<String> scopes, Instant expiresAt) {
        return new AuthenticatedSecurityContext(
                principal, Set.copyOf(scopes), Objects.requireNonNull(expiresAt, "expiresAt"));
    }

    /**
     * Returns the authenticated identity, or {@code null} for an anonymous caller.
     *
     * @return the principal, or {@code null}
     */
    @Nullable
    Principal principal();

    /**
     * Returns the scopes the caller was granted, as plain OAuth scope strings, expanded by whoever
     * authenticated the caller: a scope implied by a broader one is listed too, since scopes are compared
     * exactly and only the application knows its hierarchy.
     *
     * @return the granted scopes; empty for an anonymous caller
     */
    Set<String> scopes();

    /**
     * Returns when the caller's credentials stop being valid, or {@code null} if they do not expire:
     * an anonymous caller, or a provider whose credentials have no expiry, such as an API key. Tachyon
     * closes every SSE stream a request opened, the 2025-11-25 {@code GET} stream, a streamed response
     * and a {@code subscriptions/listen} stream, at this instant, and never before it; the client then
     * reconnects with fresh credentials. A context with no expiry closes nothing.
     *
     * @return the expiry, or {@code null}
     */
    default @Nullable Instant expiresAt() {
        return null;
    }

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
