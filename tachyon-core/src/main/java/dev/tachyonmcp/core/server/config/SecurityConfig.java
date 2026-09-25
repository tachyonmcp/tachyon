/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.security.AuthenticationProvider;
import dev.tachyonmcp.api.server.security.BearerTokenVerifier;
import dev.tachyonmcp.core.server.security.BearerTokenAuthenticationProvider;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Configures request authentication. Without an {@link AuthenticationProvider} every request is
 * anonymous and no authentication handler is installed.
 *
 * @param authenticationProvider resolves the security context of every HTTP request, or {@code null}
 *     to leave requests anonymous
 */
@ExperimentalApi
public record SecurityConfig(@Nullable AuthenticationProvider<? super HttpRequest> authenticationProvider) {

    private static final SecurityConfig DISABLED = new SecurityConfig(null);

    /** Returns the configuration that authenticates no requests. */
    public static SecurityConfig disabled() {
        return DISABLED;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SecurityConfig}. */
    public static final class Builder {

        private @Nullable AuthenticationProvider<? super HttpRequest> authenticationProvider;

        private Builder() {}

        /**
         * Authenticates every HTTP request with {@code provider}.
         *
         * @param provider resolves the caller's security context
         * @return this builder
         */
        public Builder authenticationProvider(AuthenticationProvider<? super HttpRequest> provider) {
            this.authenticationProvider = Objects.requireNonNull(provider, "provider");
            return this;
        }

        /**
         * Requires an OAuth 2.1 bearer token on every HTTP request, verified by {@code verifier}.
         * Shorthand for {@code authenticationProvider(new BearerTokenAuthenticationProvider(verifier))}.
         *
         * @param verifier verifies the token and maps it to a security context
         * @return this builder
         */
        public Builder bearerToken(BearerTokenVerifier verifier) {
            return authenticationProvider(new BearerTokenAuthenticationProvider(verifier));
        }

        public SecurityConfig build() {
            return authenticationProvider == null ? DISABLED : new SecurityConfig(authenticationProvider);
        }
    }
}
