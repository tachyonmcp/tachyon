/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.config.SecurityConfig.Authentication;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.AuthenticationProvider;
import dev.tachyonmcp.core.server.security.BearerTokenAuthenticationProvider;
import dev.tachyonmcp.core.server.security.BearerTokenVerifier;
import dev.tachyonmcp.core.server.security.VerifiedToken;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * How {@link SecurityConfig} turns the two ways of authenticating into one value, so that what the
 * server publishes and the provider that decides cannot disagree, and that a bad bearer configuration
 * fails when the server is configured, not on the first request. The rules of the bearer configuration
 * itself are {@code BearerConfigTest}'s.
 */
class SecurityConfigTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");
    private static final URI AUTHORIZATION_SERVER = URI.create("https://auth.example.com");
    private static final BearerTokenVerifier VERIFIER =
            token -> new VerifiedToken(() -> "alice", Set.of(), CLOCK.instant().plusSeconds(60), Set.of(RESOURCE));

    private static SecurityConfig.Builder bearer() {
        return SecurityConfig.builder()
                .accessPolicy(AccessPolicy.permitAll())
                .bearerToken(b -> b.resource(RESOURCE)
                        .authorizationServers(AUTHORIZATION_SERVER)
                        .scopesSupported("tools:read")
                        .verifier(VERIFIER));
    }

    @Test
    void bearerTokensAreOneValueHoldingTheConfigurationAndTheProviderBuiltFromIt() {
        final var config = bearer().build(CLOCK);

        assertThat(config.authentication()).isInstanceOfSatisfying(Authentication.Bearer.class, bearer -> {
            assertThat(bearer.config().resource()).isEqualTo(RESOURCE);
            assertThat(bearer.config().authorizationServers()).containsExactly(AUTHORIZATION_SERVER);
            assertThat(bearer.config().scopesSupported()).containsExactly("tools:read");
            assertThat(bearer.config().verifier()).isSameAs(VERIFIER);
            assertThat(bearer.provider()).isInstanceOf(BearerTokenAuthenticationProvider.class);
            assertThat(config.authenticationProvider()).isSameAs(bearer.provider());
        });
    }

    @Test
    void aBadBearerConfigurationFailsWhenConfiguredNotWhenBuilt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SecurityConfig.builder()
                        .bearerToken(b -> b.resource(RESOURCE).verifier(VERIFIER)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SecurityConfig.builder()
                        .bearerToken(b ->
                                b.authorizationServers(AUTHORIZATION_SERVER).verifier(VERIFIER)));
    }

    @Test
    void nothingConfiguredAuthenticatesNoOne() {
        final var config = SecurityConfig.builder().build(CLOCK);

        assertThat(config.authentication()).isInstanceOf(Authentication.Disabled.class);
        assertThat(config.authenticationProvider()).isNull();
        assertThat(config).isEqualTo(SecurityConfig.disabled());
    }

    @Test
    void aCustomProviderPublishesNothing() {
        final AuthenticationProvider custom = request -> SecurityContext.anonymous();

        final var config = SecurityConfig.builder()
                .authenticationProvider(custom)
                .accessPolicy(AccessPolicy.permitAll())
                .build(CLOCK);

        assertThat(config.authentication()).isEqualTo(new Authentication.Custom(custom));
        assertThat(config.authenticationProvider()).isSameAs(custom);
    }

    /** A server that knows who its callers are must say what they may see: nothing is left open by forgetting. */
    @Test
    void anAuthenticatingServerMustChooseItsAccessPolicy() {
        final AuthenticationProvider custom = request -> SecurityContext.anonymous();

        for (final var unchosen : List.of(
                SecurityConfig.builder().authenticationProvider(custom),
                SecurityConfig.builder()
                        .bearerToken(b -> b.resource(RESOURCE)
                                .authorizationServers(AUTHORIZATION_SERVER)
                                .verifier(VERIFIER)))) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> unchosen.build(CLOCK))
                    .withMessageContaining("accessPolicy(AccessPolicy.permitAll())")
                    .withMessageContaining("your own AccessPolicy");
        }
        final AccessPolicy policy = (caller, feature) -> caller.isAuthenticated();
        assertThat(SecurityConfig.builder()
                        .authenticationProvider(custom)
                        .accessPolicy(policy)
                        .build(CLOCK)
                        .accessPolicy())
                .isSameAs(policy);
    }

    /** Without authentication every caller is anonymous: the policy is optional and lets everything through. */
    @Test
    void aServerThatAuthenticatesNobodyMayLeaveThePolicyOut() {
        final var anonymous = SecurityContext.anonymous();
        final var tool = ToolDescriptor.of("any");
        final AccessPolicy noTools = (caller, feature) -> !(feature instanceof ToolDescriptor);

        assertThat(SecurityConfig.disabled().accessPolicy().permits(anonymous, tool))
                .isTrue();
        final var chosen = SecurityConfig.builder().accessPolicy(noTools).build(CLOCK);
        assertThat(chosen.authentication()).isInstanceOf(Authentication.Disabled.class);
        assertThat(chosen.accessPolicy()).isSameAs(noTools);
    }

    @Test
    void theLastWayOfAuthenticatingWinsAndLeavesNoStateOfTheOther() {
        final AuthenticationProvider custom = request -> SecurityContext.anonymous();

        final var customLast = bearer().authenticationProvider(custom).build(CLOCK);
        final var bearerLast = SecurityConfig.builder()
                .accessPolicy(AccessPolicy.permitAll())
                .authenticationProvider(custom)
                .bearerToken(b -> b.resource(RESOURCE)
                        .authorizationServers(AUTHORIZATION_SERVER)
                        .verifier(token -> {
                            throw new IllegalStateException();
                        }))
                .build(CLOCK);

        assertThat(customLast.authentication()).isEqualTo(new Authentication.Custom(custom));
        assertThat(bearerLast.authentication()).isInstanceOfSatisfying(Authentication.Bearer.class, bearer -> {
            assertThat(bearer.config().resource()).isEqualTo(RESOURCE);
            assertThat(bearer.provider()).isNotSameAs(custom);
        });
    }

    @Test
    void aBearerConfigurationCannotBePairedWithAnotherProvider() {
        assertThat(Authentication.class.isSealed()).isTrue();
        assertThat(Authentication.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(
                        Authentication.Disabled.class, Authentication.Bearer.class, Authentication.Custom.class);
        assertThat(Authentication.Bearer.class.getConstructors())
                .as("only SecurityConfig.Builder derives a Bearer, from its configuration")
                .isEmpty();
    }

    @Test
    void theTimeoutAppliesToEitherWayAndMustBePositive() {
        assertThat(bearer().authenticationTimeout(Duration.ofSeconds(3))
                        .build(CLOCK)
                        .authenticationTimeout())
                .isEqualTo(Duration.ofSeconds(3));
        assertThatIllegalArgumentException().isThrownBy(() -> bearer().authenticationTimeout(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityConfig(
                        new Authentication.Disabled(), AccessPolicy.permitAll(), Duration.ofSeconds(-1), 1));
    }

    @Test
    void thePendingAuthenticationsAreBoundedAndTheBoundIsPositive() {
        assertThat(bearer().build(CLOCK).maxPendingAuthentications())
                .isEqualTo(SecurityConfig.DEFAULT_MAX_PENDING_AUTHENTICATIONS);
        assertThat(bearer().maxPendingAuthentications(7).build(CLOCK).maxPendingAuthentications())
                .isEqualTo(7);
        assertThatIllegalArgumentException().isThrownBy(() -> bearer().maxPendingAuthentications(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityConfig(
                        new Authentication.Disabled(), AccessPolicy.permitAll(), Duration.ofSeconds(1), 0));
    }
}
