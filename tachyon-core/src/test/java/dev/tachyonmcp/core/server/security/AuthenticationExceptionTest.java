/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * RFC 9110 §11.6.1: a {@code 401} MUST carry a challenge, so one cannot be built without it. RFC 6750
 * §3: the {@code Bearer} scheme "MUST be followed by one or more auth-param values", so no factory
 * sends a bare one. §3.1 pairs each Bearer error with its status; the two Bearer factories keep the
 * pair together.
 */
class AuthenticationExceptionTest {

    @Test
    void theBearerRejectionsHaveTheirExactStatusAndAChallengeWithAnAuthParam() {
        final var invalidToken = AuthenticationException.bearerInvalidToken("Expired");
        final var invalidRequest = AuthenticationException.bearerInvalidRequest("Two tokens");

        assertThat(invalidToken.status()).isEqualTo(401);
        assertThat(invalidToken.wwwAuthenticate()).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(invalidRequest.status()).isEqualTo(400);
        assertThat(invalidRequest.wwwAuthenticate()).isEqualTo("Bearer error=\"invalid_request\"");
        assertThat(invalidToken.getMessage()).isEqualTo("Expired");
    }

    @Test
    void anUnauthorizedRejectionCarriesTheChallengeOfItsOwnScheme() {
        final var rejection = AuthenticationException.unauthorized(
                "No API key", AuthenticationChallenge.of("ApiKey").withParam("realm", "mcp"));

        assertThat(rejection.status()).isEqualTo(401);
        assertThat(rejection.wwwAuthenticate()).isEqualTo("ApiKey realm=\"mcp\"");
    }

    @Test
    void anUnauthorizedRejectionWithoutAChallengeCannotBeBuilt() {
        assertThatNullPointerException().isThrownBy(() -> AuthenticationException.unauthorized("No key", null));
    }

    @Test
    void aBadRequestMayOrMayNotCarryAChallenge() {
        final var plain = AuthenticationException.badRequest("Two keys");
        final var challenged = AuthenticationException.badRequest("Two keys", AuthenticationChallenge.of("ApiKey"));

        assertThat(plain.status()).isEqualTo(400);
        assertThat(plain.wwwAuthenticate()).isNull();
        assertThat(challenged.status()).isEqualTo(400);
        assertThat(challenged.wwwAuthenticate()).isEqualTo("ApiKey");
    }

    /** RFC 6750 §3.1: {@code insufficient_scope} is a {@code 403}; the challenge is the server's, in its scheme. */
    @Test
    void aForbiddenCarriesEveryRequiredScopeInOrderAndNoChallengeOfItsOwn() {
        final var forbidden = AuthenticationException.insufficientScope(new LinkedHashSet<>(List.of("b", "a")));

        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.wwwAuthenticate()).isNull();
        assertThat(forbidden.requiredScopes()).containsExactly("b", "a");
        assertThatIllegalArgumentException().isThrownBy(() -> AuthenticationException.insufficientScope(Set.of()));
    }

    @Test
    void aCheckThatCannotBeMadeIsA503WithNoChallenge() {
        final var down = AuthenticationException.unavailable("down");

        assertThat(down.status()).isEqualTo(503);
        assertThat(down.wwwAuthenticate()).isNull();
        assertThat(down.requiredScopes()).isEmpty();
    }
}
