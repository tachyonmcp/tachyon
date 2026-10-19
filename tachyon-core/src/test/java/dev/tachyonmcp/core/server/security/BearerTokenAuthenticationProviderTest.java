/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.core.server.config.BearerConfig;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MCP authorization (2025-11-25, 2026-07-28) § Token Handling: a server MUST validate that a token was
 * issued for it as the audience (RFC 8707) and reject an expired one with {@code 401}. Tachyon does
 * both, so a verifier that forgets to cannot let a token for another server through.
 */
class BearerTokenAuthenticationProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");
    private static final Principal ALICE = () -> "alice";

    private static final URI AUTHORIZATION_SERVER = URI.create("https://auth.example.com");
    private static final String INVALID_TOKEN = "Bearer error=\"invalid_token\", resource_metadata=\""
            + "https://mcp.example.com/.well-known/oauth-protected-resource/mcp\", scope=\"tools:call tools:read\"";

    private static BearerTokenAuthenticationProvider provider(
            URI resource, String[] scopes, BearerTokenVerifier verifier) {
        return new BearerTokenAuthenticationProvider(
                BearerConfig.builder()
                        .resource(resource)
                        .authorizationServers(AUTHORIZATION_SERVER)
                        .scopesSupported(scopes)
                        .verifier(verifier)
                        .build(),
                CLOCK);
    }

    private static BearerTokenAuthenticationProvider provider(URI resource, Instant expiresAt, URI... audience) {
        return provider(
                resource,
                new String[] {"tools:call", "tools:read"},
                token -> new VerifiedToken(ALICE, Set.of("tools:call"), expiresAt, Set.of(audience)));
    }

    private static BearerTokenAuthenticationProvider provider(BearerTokenVerifier verifier) {
        return provider(RESOURCE, new String[] {"tools:call", "tools:read"}, verifier);
    }

    private static AuthenticationRequest bearer() {
        return bearer("abc");
    }

    private static AuthenticationRequest bearer(String token) {
        return new AuthenticationRequest("POST", "/mcp", name -> List.of("Bearer " + token));
    }

    private static void assertRejectedAsInvalidToken(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(AuthenticationException.class, e -> {
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.wwwAuthenticate()).isEqualTo(INVALID_TOKEN);
        });
    }

    /** MCP authorization § Protected Resource Metadata Discovery: the challenge says where the metadata is. */
    @Test
    void aRequestWithoutCredentialsIsChallengedWithTheMetadataUrlAndTheScopes() {
        final var provider = provider(RESOURCE, NOW.plusSeconds(60), RESOURCE);
        final var anonymous = new AuthenticationRequest("POST", "/mcp", name -> List.of());
        final var basic = new AuthenticationRequest("POST", "/mcp", name -> List.of("Basic YWxpY2U6c2VjcmV0"));

        for (final var request : List.of(anonymous, basic)) {
            assertThatThrownBy(() -> provider.authenticate(request))
                    .isInstanceOfSatisfying(AuthenticationException.class, e -> {
                        assertThat(e.status()).isEqualTo(401);
                        assertThat(e.wwwAuthenticate())
                                .isEqualTo("Bearer resource_metadata=\""
                                        + "https://mcp.example.com/.well-known/oauth-protected-resource/mcp\", "
                                        + "scope=\"tools:call tools:read\"");
                    });
        }
    }

    /**
     * RFC 6750 §3.1: {@code insufficient_scope} answers {@code 403}. MCP authorization § Runtime
     * Insufficient Scope, § Server Scope Management: every required scope, in one challenge, with the
     * metadata URL. Whatever a {@code 401} carried, the server answers with its own; a {@code 400} and a
     * {@code 503} pass as thrown.
     */
    @Test
    void theChallengeForARejectionIsTheServersOwnForA401AndA403() {
        final var provider = provider(RESOURCE, NOW.plusSeconds(60), RESOURCE);
        final var metadata = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";

        assertThat(provider.challengeFor(AuthenticationException.insufficientScope(
                        new LinkedHashSet<>(List.of("tools:call", "admin:audit")))))
                .isEqualTo("Bearer error=\"insufficient_scope\", scope=\"tools:call admin:audit\", "
                        + "resource_metadata=\"" + metadata + "\"");
        assertThat(provider.challengeFor(AuthenticationException.insufficientScope(
                        new LinkedHashSet<>(List.of("admin:audit", "tools:call")))))
                .as("the order a tool declared is the order reported")
                .contains("scope=\"admin:audit tools:call\"");
        final var invalidToken = "Bearer error=\"invalid_token\", resource_metadata=\"" + metadata
                + "\", scope=\"tools:call tools:read\"";
        assertThat(provider.challengeFor(AuthenticationException.bearerInvalidToken("revoked")))
                .isEqualTo(invalidToken);
        assertThat(provider.challengeFor(AuthenticationException.unauthorized(
                        "elsewhere", AuthenticationChallenge.of("Bearer").withParam("realm", "x"))))
                .as("whatever challenge the thrower chose")
                .isEqualTo(invalidToken);
        assertThat(provider.challengeFor(AuthenticationException.bearerInvalidRequest("two")))
                .isEqualTo("Bearer error=\"invalid_request\"");
        assertThat(provider.challengeFor(AuthenticationException.unavailable("down")))
                .isNull();
    }

    @Test
    void aServerWithoutScopesOmitsTheScopeParameter() {
        final var provider = provider(RESOURCE, new String[0], token -> {
            throw AuthenticationException.bearerInvalidToken("Unknown token");
        });

        assertThatThrownBy(() -> provider.authenticate(bearer()))
                .isInstanceOfSatisfying(
                        AuthenticationException.class,
                        e -> assertThat(e.wwwAuthenticate())
                                .isEqualTo("Bearer error=\"invalid_token\", resource_metadata=\""
                                        + "https://mcp.example.com/.well-known/oauth-protected-resource/mcp\""));
    }

    @Test
    void theChallengeOfARejectionByTheVerifierIsTheServersOwn() {
        final var challenge = AuthenticationChallenge.of("Bearer").withParam("realm", "elsewhere");
        final var provider = provider(token -> {
            throw AuthenticationException.unauthorized("Revoked", challenge);
        });

        assertThatThrownBy(() -> provider.authenticate(bearer()))
                .satisfies(BearerTokenAuthenticationProviderTest::assertRejectedAsInvalidToken)
                .hasMessage("Revoked");
    }

    /** RFC 6750 §3.1: a malformed request is a {@code 400}, and finding the authorization server does not fix it. */
    @Test
    void aBadRequestIsNotChallengedWithDiscovery() {
        final var provider = provider(token -> {
            throw AuthenticationException.bearerInvalidRequest("Token in the wrong place");
        });
        final var severalHeaders =
                new AuthenticationRequest("POST", "/mcp", name -> List.of("Bearer abc", "Bearer def"));
        final var queryToken = new AuthenticationRequest("POST", "/mcp?access_token=abc", name -> List.of());

        for (final var request : List.of(bearer(), severalHeaders, queryToken)) {
            assertThatThrownBy(() -> provider.authenticate(request))
                    .isInstanceOfSatisfying(AuthenticationException.class, e -> {
                        assertThat(e.status()).isEqualTo(400);
                        assertThat(e.wwwAuthenticate()).isEqualTo("Bearer error=\"invalid_request\"");
                    });
        }
    }

    @Test
    void acceptsATokenForThisServerAndHandsOverWhatTheVerifierReportedUnchanged() throws Exception {
        final var expiresAt = NOW.plusSeconds(60);

        final var context = provider(RESOURCE, expiresAt, RESOURCE).authenticate(bearer());

        assertThat(context.principal()).isSameAs(ALICE);
        assertThat(context.scopes()).containsExactly("tools:call");
        assertThat(context.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void acceptsATokenWhenOneOfSeveralAudiencesIsThisServer() throws Exception {
        final var other = URI.create("https://other.example.com");

        final var context =
                provider(RESOURCE, NOW.plusSeconds(60), other, RESOURCE).authenticate(bearer());

        assertThat(context.isAuthenticated()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://other.example.com/mcp",
                "https://mcp.example.com/other",
                "https://mcp.example.com/mcp/",
                "https://mcp.example.com/MCP",
                "https://mcp.example.com:8443/mcp",
                "http://mcp.example.com/mcp",
                "https://mcp.example.com/mcp?x=1",
                "https://mcp.example.com/mcp#section",
                "https://mcp.example.com",
                "https://alice@mcp.example.com/mcp",
                "mcp",
                "urn:example:mcp"
            })
    void rejectsATokenIssuedForAnotherResource(String audience) {
        final var provider = provider(RESOURCE, NOW.plusSeconds(60), URI.create(audience));

        assertThatThrownBy(() -> provider.authenticate(bearer()))
                .satisfies(BearerTokenAuthenticationProviderTest::assertRejectedAsInvalidToken);
    }

    /** K13: the owner key is {@code issuer|name}, so one subject from two issuers is two owners. */
    @Test
    void theSameSubjectFromTwoIssuersIsTwoOwners() throws Exception {
        final var issuerA = URI.create("https://a.example.com");
        final var issuerB = URI.create("https://b.example.com");
        final var config = BearerConfig.builder()
                .resource(RESOURCE)
                .authorizationServers(issuerA, issuerB)
                .verifier(token -> new VerifiedToken(
                        ALICE,
                        Set.of(),
                        NOW.plusSeconds(60),
                        Set.of(RESOURCE),
                        token.equals("from-a") ? issuerA : issuerB))
                .build();
        final var provider = new BearerTokenAuthenticationProvider(config, CLOCK);

        final var fromA = provider.authenticate(bearer("from-a"));
        final var fromB = provider.authenticate(bearer("from-b"));

        assertThat(fromA.principal().getName())
                .isEqualTo(fromB.principal().getName())
                .isEqualTo("alice");
        assertThat(fromA.ownerKey()).isEqualTo("https://a.example.com|alice");
        assertThat(fromB.ownerKey()).isEqualTo("https://b.example.com|alice");
        assertThat(Owner.of(fromA)).isNotEqualTo(Owner.of(fromB));
    }

    @Test
    void aTokenWithoutAnIssuerTakesTheOneAuthorizationServer() throws Exception {
        final var context = provider(RESOURCE, NOW.plusSeconds(60), RESOURCE).authenticate(bearer());

        assertThat(context.ownerKey()).isEqualTo(AUTHORIZATION_SERVER + "|alice");
    }

    /** With several issuers, a verifier that names none would let two subjects collide: that is a bug, not a token. */
    @Test
    void aTokenWithoutAnIssuerOnAServerWithSeveralAuthorizationServersIsAProviderFailure() {
        final var config = BearerConfig.builder()
                .resource(RESOURCE)
                .authorizationServers(URI.create("https://a.example.com"), URI.create("https://b.example.com"))
                .verifier(token -> new VerifiedToken(ALICE, Set.of(), NOW.plusSeconds(60), Set.of(RESOURCE)))
                .build();
        final var provider = new BearerTokenAuthenticationProvider(config, CLOCK);

        assertThatThrownBy(() -> provider.authenticate(bearer()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(AuthenticationException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void rejectsATokenWithNoAudienceAtAll() {
        final var provider = provider(RESOURCE, NOW.plusSeconds(60));

        assertThatThrownBy(() -> provider.authenticate(bearer()))
                .satisfies(BearerTokenAuthenticationProviderTest::assertRejectedAsInvalidToken);
    }

    /** RFC 3986 §6.2.2.1 and §6.2.3: scheme and host are case-insensitive; a default port and an empty path are implied. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTPS://MCP.Example.COM/mcp",
                "https://mcp.example.com:443/mcp",
                "https://Mcp.Example.Com:443/mcp"
            })
    void treatsTheSameResourceWrittenAnotherWayAsEqual(String audience) throws Exception {
        final var context =
                provider(RESOURCE, NOW.plusSeconds(60), URI.create(audience)).authenticate(bearer());

        assertThat(context.isAuthenticated()).isTrue();
    }

    @Test
    void anEmptyPathAndASlashAreTheSameResource() throws Exception {
        final var bare = URI.create("https://mcp.example.com");

        final var withSlash = provider(bare, NOW.plusSeconds(60), URI.create("https://mcp.example.com/"));
        final var withoutSlash = provider(URI.create("https://mcp.example.com/mcp"), NOW.plusSeconds(60), bare);

        assertThat(withSlash.authenticate(bearer()).isAuthenticated()).isTrue();
        assertThatThrownBy(() -> withoutSlash.authenticate(bearer()))
                .satisfies(BearerTokenAuthenticationProviderTest::assertRejectedAsInvalidToken);
    }

    @Test
    void rejectsATokenThatExpiresExactlyNow() {
        final var provider = provider(RESOURCE, NOW, RESOURCE);

        assertThatThrownBy(() -> provider.authenticate(bearer()))
                .satisfies(BearerTokenAuthenticationProviderTest::assertRejectedAsInvalidToken);
    }

    @Test
    void rejectsAnExpiredTokenAndAcceptsOneThatLastsAnotherNanosecond() throws Exception {
        final var expired = provider(RESOURCE, NOW.minusSeconds(1), RESOURCE);
        final var lastsAMoment = provider(RESOURCE, NOW.plusNanos(1), RESOURCE);

        assertThatThrownBy(() -> expired.authenticate(bearer()))
                .satisfies(BearerTokenAuthenticationProviderTest::assertRejectedAsInvalidToken);
        assertThat(lastsAMoment.authenticate(bearer()).isAuthenticated())
                .as("no skew is tolerated either way")
                .isTrue();
    }
}
