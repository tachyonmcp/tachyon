/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.AuthenticationChallenge;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.core.server.security.AuthenticationProvider;
import dev.tachyonmcp.core.server.security.BearerTokenAuthenticationProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Configures request authentication. Without authentication every request is anonymous and no
 * authentication handler is installed.
 *
 * @param authentication how requests are authenticated: one of {@link Authentication}'s three values,
 *     so the provider that answers a request and what the server publishes about it cannot disagree
 * @param accessPolicy which features a caller may see; always present. {@link
 *     AccessPolicy#permitAll()} for a server that authenticates nobody, and, for one that does, the
 *     policy its builder was told to use, since {@link Builder#build} refuses to choose for you
 * @param authenticationTimeout how long a request may wait for the authentication provider before it is
 *     answered {@code 503 Service Unavailable}; positive
 */
@ExperimentalApi
public record SecurityConfig(Authentication authentication, AccessPolicy accessPolicy, Duration authenticationTimeout) {

    /** How long a request waits for its authentication unless configured otherwise. */
    public static final Duration DEFAULT_AUTHENTICATION_TIMEOUT = Duration.ofSeconds(10);

    private static final SecurityConfig DISABLED =
            new SecurityConfig(new Authentication.Disabled(), AccessPolicy.permitAll(), DEFAULT_AUTHENTICATION_TIMEOUT);

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if {@code authenticationTimeout} is zero or negative
     */
    public SecurityConfig {
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(accessPolicy, "accessPolicy");
        Objects.requireNonNull(authenticationTimeout, "authenticationTimeout");
        if (authenticationTimeout.isZero() || authenticationTimeout.isNegative()) {
            throw new IllegalArgumentException("authenticationTimeout must be positive");
        }
    }

    /** Returns the configuration that authenticates no requests. */
    public static SecurityConfig disabled() {
        return DISABLED;
    }

    /**
     * Returns the provider that authenticates every HTTP request.
     *
     * @return the provider, or {@code null} when requests are anonymous
     */
    public @Nullable AuthenticationProvider authenticationProvider() {
        return switch (authentication) {
            case Authentication.Disabled disabled -> null;
            case Authentication.Bearer bearer -> bearer.provider();
            case Authentication.Custom custom -> custom.provider();
        };
    }

    /**
     * How a server authenticates requests. A single value, because what the server publishes about its
     * authentication (OAuth metadata, challenges) and the provider that decides must be one thing: a
     * bearer configuration cannot be paired with another provider, since only the server builds
     * {@link Bearer}.
     */
    @ExperimentalApi
    public sealed interface Authentication {

        /** Every request is anonymous; nothing is published. */
        record Disabled() implements Authentication {}

        /**
         * OAuth 2.1 bearer tokens. The provider is built from the configuration, with the server's
         * clock, and cannot be supplied.
         */
        final class Bearer implements Authentication {

            private final BearerConfig config;
            private final BearerTokenAuthenticationProvider provider;

            private Bearer(BearerConfig config, Clock clock) {
                this.config = config;
                this.provider = new BearerTokenAuthenticationProvider(config, clock);
            }

            /**
             * Returns what the server knows and publishes about its bearer tokens.
             *
             * @return the bearer-token configuration
             */
            public BearerConfig config() {
                return config;
            }

            /**
             * Returns the provider built from {@link #config()}.
             *
             * @return the bearer-token provider
             */
            public AuthenticationProvider provider() {
                return provider;
            }

            /**
             * Returns the challenge of the {@code 403} that answers a caller who lacks scopes a request
             * requires; see {@link BearerTokenAuthenticationProvider#insufficientScope}.
             *
             * @param requiredScopes every scope the request requires, in the order to report them
             * @return the challenge
             */
            public AuthenticationChallenge insufficientScope(Set<String> requiredScopes) {
                return provider.insufficientScope(requiredScopes);
            }

            /**
             * Returns the challenge of a {@code 401} thrown anywhere a request is served; see {@link
             * BearerTokenAuthenticationProvider#unauthorized}.
             *
             * @param rejection a rejection with status {@code 401}
             * @return the challenge to send
             */
            public AuthenticationChallenge unauthorized(AuthenticationException rejection) {
                return provider.unauthorized(rejection);
            }
        }

        /**
         * A provider of the application's own, which decides everything, including the challenge of a
         * rejection. The server publishes nothing about it.
         *
         * @param provider resolves the security context of every HTTP request
         */
        record Custom(AuthenticationProvider provider) implements Authentication {

            /** Checks that there is a provider. */
            public Custom {
                Objects.requireNonNull(provider, "provider");
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SecurityConfig}. */
    public static final class Builder {

        private @Nullable AuthenticationProvider custom;
        private @Nullable BearerConfig bearer;
        private @Nullable AccessPolicy accessPolicy;
        private Duration authenticationTimeout = DEFAULT_AUTHENTICATION_TIMEOUT;

        private Builder() {}

        /**
         * Authenticates every HTTP request with {@code provider}, in place of any bearer
         * configuration. The provider decides everything, including the challenge of a rejection.
         *
         * @param provider resolves the caller's security context
         * @return this builder
         */
        public Builder authenticationProvider(AuthenticationProvider provider) {
            this.custom = Objects.requireNonNull(provider, "provider");
            this.bearer = null;
            return this;
        }

        /**
         * Requires an OAuth 2.1 bearer token on every HTTP request, in place of any custom provider.
         * {@code configurer} sets the server's {@linkplain BearerConfig#resource() resource URI}, its
         * {@linkplain BearerConfig#authorizationServers() authorization servers}, the
         * {@linkplain BearerConfig#scopesSupported() scopes} it supports and the
         * {@linkplain BearerConfig#verifier() verifier}:
         *
         * <pre>{@code
         * security.bearerToken(b -> b.resource(URI.create("https://mcp.example.com/mcp"))
         *         .authorizationServers(URI.create("https://auth.example.com"))
         *         .scopesSupported("tools:read", "tools:call")
         *         .verifier(verifier));
         * }</pre>
         *
         * <p>The settings are checked here, so a bad configuration fails when the server is
         * configured, not on the first request: there must be a resource, at least one authorization
         * server and a verifier. Tachyon takes the token from the {@code Authorization} header, hands
         * it to the verifier, and then enforces what MCP authorization requires and verifiers most often
         * forget: the token must not have expired, and its audience must include the resource (RFC
         * 8707). Either failing is a {@code 401} with {@code error="invalid_token"}. Every {@code 401}
         * tells the client where to find the server's RFC 9728 Protected Resource Metadata, which the
         * server serves, so a client can discover the authorization server.
         *
         * @param configurer sets the bearer configuration
         * @return this builder
         * @throws IllegalArgumentException if the configuration breaks the rules of {@link BearerConfig}
         */
        public Builder bearerToken(Consumer<BearerConfig.Builder> configurer) {
            final var builder = BearerConfig.builder();
            configurer.accept(builder);
            this.bearer = builder.build();
            this.custom = null;
            return this;
        }

        /**
         * Bounds how long a request waits for the authentication provider, which defaults to {@link
         * SecurityConfig#DEFAULT_AUTHENTICATION_TIMEOUT} (10 seconds). A request still unanswered when it elapses is
         * answered {@code 503 Service Unavailable} with {@code Retry-After} and no challenge, so the
         * client keeps a token that may be valid, and the connection closes. The provider is not
         * interrupted: it keeps running until it returns, and its late result is ignored.
         *
         * @param timeout the longest wait, positive
         * @return this builder
         * @throws IllegalArgumentException if {@code timeout} is zero or negative
         */
        public Builder authenticationTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("authenticationTimeout must be positive");
            }
            this.authenticationTimeout = timeout;
            return this;
        }

        /**
         * Decides which features a caller may see: tools, resources, resource templates and prompts the
         * policy refuses are neither listed nor reachable, and a request that names one is answered like
         * a request for a feature that does not exist. Required when requests are authenticated
         * ({@link #bearerToken}, {@link #authenticationProvider}): a server that knows who its callers
         * are must also say what they may see, even if the answer is {@link AccessPolicy#permitAll()},
         * so that nothing is left open by forgetting. Optional for a server that authenticates nobody,
         * where every caller is anonymous and the policy defaults to {@link AccessPolicy#permitAll()}.
         *
         * @param policy pure and in memory; see {@link AccessPolicy}
         * @return this builder
         */
        public Builder accessPolicy(AccessPolicy policy) {
            this.accessPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @param clock the server's clock, which decides whether a bearer token has expired
         * @return the configuration
         * @throws IllegalStateException if requests are authenticated and no {@linkplain
         *     #accessPolicy(AccessPolicy) access policy} was chosen
         */
        public SecurityConfig build(Clock clock) {
            Objects.requireNonNull(clock, "clock");
            final var authenticated = bearer != null || custom != null;
            if (authenticated && accessPolicy == null) {
                throw new IllegalStateException(
                        "Requests are authenticated, so the server must also say what its callers may see: configure "
                                + "security(s -> s.accessPolicy(AccessPolicy.permitAll())) to let every authenticated "
                                + "caller see everything, or pass your own AccessPolicy");
            }
            final var policy = accessPolicy != null ? accessPolicy : AccessPolicy.permitAll();
            if (bearer != null) {
                return new SecurityConfig(new Authentication.Bearer(bearer, clock), policy, authenticationTimeout);
            }
            if (custom != null) {
                return new SecurityConfig(new Authentication.Custom(custom), policy, authenticationTimeout);
            }
            return accessPolicy == null
                    ? DISABLED
                    : new SecurityConfig(new Authentication.Disabled(), policy, authenticationTimeout);
        }
    }
}
