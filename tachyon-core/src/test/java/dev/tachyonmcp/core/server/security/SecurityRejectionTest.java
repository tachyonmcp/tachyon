/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.config.SecurityConfig.Authentication;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one mapping from a refusal of the caller to its answer, shared by the request head and dispatch.
 * Bearer-token mode answers a {@code 401} with one of its own two challenges, whoever threw it; another
 * scheme answers with what was thrown.
 */
class SecurityRejectionTest {

    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");
    private static final String METADATA = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";

    private final Authentication.Bearer bearer = (Authentication.Bearer) SecurityConfig.builder()
            .accessPolicy(AccessPolicy.permitAll())
            .bearerToken(b -> b.resource(RESOURCE)
                    .authorizationServers(URI.create("https://auth.example.com"))
                    .verifier(token -> new VerifiedToken(() -> "alice", Set.of(), Instant.MAX, Set.of(RESOURCE))))
            .build(Clock.systemUTC())
            .authentication();
    private final Authentication custom = new Authentication.Custom(request -> SecurityContext.anonymous());

    @Test
    void bearerModeAnswersEveryUnauthorizedWithItsOwnChallenge() throws Exception {
        final AuthenticationException noToken;
        try {
            bearer.provider().authenticate(new AuthenticationRequest("POST", "/mcp", name -> List.of()));
            throw new AssertionError("a request without a token is refused");
        } catch (AuthenticationException e) {
            noToken = e;
        }

        assertThat(SecurityRejection.of(noToken, bearer))
                .as("the provider's own no-token challenge passes unchanged")
                .isEqualTo(new SecurityRejection(401, "Bearer resource_metadata=\"" + METADATA + "\"", null));
        assertThat(SecurityRejection.of(AuthenticationException.bearerInvalidToken("revoked"), bearer))
                .as("any other 401 gets the invalid_token challenge that names the metadata")
                .isEqualTo(new SecurityRejection(
                        401, "Bearer error=\"invalid_token\", resource_metadata=\"" + METADATA + "\"", null));
        assertThat(SecurityRejection.of(AuthenticationException.bearerInvalidRequest("two tokens"), bearer))
                .isEqualTo(new SecurityRejection(400, "Bearer error=\"invalid_request\"", null));
        assertThat(SecurityRejection.of(
                        AuthenticationException.insufficientScope(new LinkedHashSet<>(List.of("b", "a"))), bearer))
                .isEqualTo(new SecurityRejection(
                        403,
                        "Bearer error=\"insufficient_scope\", scope=\"b a\", resource_metadata=\"" + METADATA + "\"",
                        null));
    }

    /** A verifier that cannot reach its authorization server says so: the client keeps its token and waits. */
    @Test
    void aCheckThatCannotBeMadeRightNowIsAServiceUnavailableWithRetryAfterAndNoChallenge() {
        final var down = AuthenticationException.unavailable("introspection is down", Duration.ofMillis(2_500));

        assertThat(SecurityRejection.of(down, bearer)).isEqualTo(new SecurityRejection(503, null, "3"));
        assertThat(SecurityRejection.of(down, custom)).isEqualTo(new SecurityRejection(503, null, "3"));
    }

    @Test
    void anotherSchemeAnswersWithWhatWasThrownAndAForbiddenWithoutAChallenge() {
        final var apiKey = AuthenticationException.unauthorized(
                "no key", AuthenticationChallenge.of("ApiKey").withParam("realm", "mcp"));

        assertThat(SecurityRejection.of(apiKey, custom))
                .isEqualTo(new SecurityRejection(401, "ApiKey realm=\"mcp\"", null));
        assertThat(SecurityRejection.of(AuthenticationException.insufficientScope(Set.of("a")), custom))
                .isEqualTo(new SecurityRejection(403, null, null));
    }

    @Test
    void anythingElseIsNotARefusalOfTheCaller() {
        assertThat(SecurityRejection.of(new IllegalStateException("bug"), bearer))
                .isNull();
        assertThat(new SecurityRejection(401, null, null).outcome())
                .isEqualTo(new OperationOutcome.Rejected(null, 401, 0));
    }
}
