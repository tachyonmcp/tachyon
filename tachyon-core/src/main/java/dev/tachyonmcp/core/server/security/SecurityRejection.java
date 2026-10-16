/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import org.jspecify.annotations.Nullable;

/**
 * What a rejection of the caller is answered with: the status, the {@code WWW-Authenticate} challenge,
 * {@code Retry-After} and the outcome reported to observers. The one place that maps a security
 * failure to them, for both the request head ({@code AuthenticationHandler}) and everything after it
 * (the dispatcher), so a rejection cannot be answered one way early and another way late.
 *
 * <p>A rejection is an {@link AuthenticationException}: {@code 401}, {@code 400}, {@code 403} or
 * {@code 503}. Anything else is not a rejection of the caller.
 *
 * @param status the HTTP status
 * @param wwwAuthenticate the challenge to send, or {@code null} for none
 * @param retryAfter the {@code Retry-After} to send, or {@code null} for none
 */
@InternalApi
public record SecurityRejection(
        int status,
        @Nullable String wwwAuthenticate,
        @Nullable String retryAfter) {

    /**
     * Maps a failure to its rejection.
     *
     * @param failure what was thrown
     * @param authentication how the server authenticates: bearer-token mode answers a {@code 401} with
     *     its own two challenges and a {@code 403} with the {@code insufficient_scope} one, another
     *     scheme answers with what the exception carries, and a {@code 403} without a challenge, since
     *     {@code insufficient_scope} is a Bearer error (RFC 6750 §3.1)
     * @return the rejection, or {@code null} if {@code failure} does not reject the caller
     */
    public static @Nullable SecurityRejection of(Throwable failure, SecurityConfig.Authentication authentication) {
        if (!(failure instanceof AuthenticationException rejection)) {
            return null;
        }
        final var challenge = authentication instanceof SecurityConfig.Authentication.Bearer bearer
                ? bearer.challengeFor(rejection)
                : rejection.wwwAuthenticate();
        final var retryAfter = rejection.retryAfterSeconds() == 0 ? null : Long.toString(rejection.retryAfterSeconds());
        return new SecurityRejection(rejection.status(), challenge, retryAfter);
    }

    /** Returns the outcome observers are told: rejected before any handler ran, with this status. */
    public OperationOutcome outcome() {
        return new OperationOutcome.Rejected(null, status, 0);
    }
}
