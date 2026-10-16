/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.tachyonmcp.api.server.security.SecurityContext;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A verifier states what it verified, and every part Tachyon enforces is required: there is no way to
 * return a token without saying when it expires or for whom it was issued.
 */
class VerifiedTokenTest {

    private static final Principal ALICE = () -> "alice";
    private static final Instant EXPIRES = Instant.parse("2026-10-15T12:00:00Z");
    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");

    @Test
    void holdsWhatTheVerifierReported() {
        final var token = new VerifiedToken(ALICE, Set.of("tools:call"), EXPIRES, Set.of(RESOURCE));

        assertThat(token.principal()).isSameAs(ALICE);
        assertThat(token.scopes()).containsExactly("tools:call");
        assertThat(token.expiresAt()).isEqualTo(EXPIRES);
        assertThat(token.audience()).containsExactly(RESOURCE);
        assertThat(token.issuer())
                .as("the server's one authorization server stands in")
                .isNull();
        assertThat(new VerifiedToken(ALICE, Set.of(), EXPIRES, Set.of(RESOURCE), URI.create("https://a.example.com"))
                        .issuer())
                .isEqualTo(URI.create("https://a.example.com"));
    }

    @Test
    void copiesTheSetsSoALaterChangeToTheVerifiersOwnDoesNotReachIt() {
        final var scopes = new HashSet<>(List.of("tools:call"));
        final var audience = new HashSet<>(List.of(RESOURCE));

        final var token = new VerifiedToken(ALICE, scopes, EXPIRES, audience);
        scopes.add("admin");
        audience.clear();

        assertThat(token.scopes()).containsExactly("tools:call");
        assertThat(token.audience()).containsExactly(RESOURCE);
        assertThat(token.issuer())
                .as("the server's one authorization server stands in")
                .isNull();
        assertThat(new VerifiedToken(ALICE, Set.of(), EXPIRES, Set.of(RESOURCE), URI.create("https://a.example.com"))
                        .issuer())
                .isEqualTo(URI.create("https://a.example.com"));
    }

    @Test
    void aTokenWithNoAudienceIsRepresentableSoThatTachyonRejectsIt() {
        final var token = new VerifiedToken(ALICE, Set.of(), EXPIRES, Set.of());

        assertThat(token.audience()).isEmpty();
    }

    @Test
    void everyPartIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new VerifiedToken(null, Set.of(), EXPIRES, Set.of(RESOURCE)));
        assertThatNullPointerException().isThrownBy(() -> new VerifiedToken(ALICE, null, EXPIRES, Set.of(RESOURCE)));
        assertThatNullPointerException()
                .as("a token that never expires has to say so; there is no silent default")
                .isThrownBy(() -> new VerifiedToken(ALICE, Set.of(), null, Set.of(RESOURCE)));
        assertThatNullPointerException().isThrownBy(() -> new VerifiedToken(ALICE, Set.of(), EXPIRES, null));
    }

    @Test
    void anAuthenticatedContextCarriesTheExpiryAndAnotherDoesNot() {
        final var expiring = SecurityContext.authenticated(ALICE, List.of("tools:call"), EXPIRES);
        final var plain = SecurityContext.authenticated(ALICE, List.of("tools:call"));

        assertThat(expiring.expiresAt()).isEqualTo(EXPIRES);
        assertThat(expiring.principal()).isSameAs(ALICE);
        assertThat(expiring.scopes()).containsExactly("tools:call");
        assertThat(plain.expiresAt()).isNull();
        assertThat(SecurityContext.anonymous().expiresAt()).isNull();
    }
}
