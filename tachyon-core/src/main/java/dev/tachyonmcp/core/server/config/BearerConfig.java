/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.server.security.BearerTokenVerifier;
import dev.tachyonmcp.core.server.security.ScopeTokens;
import dev.tachyonmcp.core.transport.netty.http.Origins;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a server that requires OAuth 2.1 bearer tokens must know: who it is, who may issue tokens for
 * it, and how to check them. All of it is checked here, when the configuration is built, so no
 * combination that cannot work can exist: a server with bearer tokens always has a resource URI to
 * check audiences against, at least one authorization server to tell clients about, and a verifier.
 *
 * <p>MCP authorization (2025-11-25, 2026-07-28) § Authorization Server Discovery makes a server
 * publish this as RFC 9728 Protected Resource Metadata and announce where it is in its {@code 401}
 * challenge.
 *
 * @param resource the canonical URI of this server as clients know it, the audience its tokens must
 *     name (RFC 8707). The public gateway URL, not the address behind it, and never derived from
 *     request headers. Absolute, {@code https} (or {@code http} for a loopback host), with no
 *     fragment, no query and no trailing slash
 * @param authorizationServers the issuers whose tokens this server accepts, which clients may use;
 *     at least one. Each an absolute {@code https} URI (or {@code http} for a loopback host) with no
 *     query and no fragment, as RFC 8414 §2 requires of an issuer identifier
 * @param scopesSupported the scopes this server understands, possibly none, listed in the metadata and
 *     in the {@code 401} challenge. Plain OAuth scope tokens (RFC 6749 §3.3), never {@code
 *     offline_access}
 * @param verifier verifies a token and reports its subject, scopes, expiry and audience
 */
@ExperimentalApi
public record BearerConfig(
        URI resource, List<URI> authorizationServers, List<String> scopesSupported, BearerTokenVerifier verifier) {

    /** The scope that asks for a refresh token; a resource server has no business advertising it. */
    private static final String OFFLINE_ACCESS = "offline_access";

    /**
     * Validates and copies the settings.
     *
     * @throws IllegalArgumentException if any setting breaks the rules above, or no authorization
     *     server is given
     */
    public BearerConfig {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(authorizationServers, "authorizationServers");
        Objects.requireNonNull(scopesSupported, "scopesSupported");
        Objects.requireNonNull(verifier, "verifier");
        requireResource(resource);
        authorizationServers = List.copyOf(new LinkedHashSet<>(authorizationServers));
        if (authorizationServers.isEmpty()) {
            throw new IllegalArgumentException("Bearer tokens need at least one authorization server");
        }
        authorizationServers.forEach(BearerConfig::requireAuthorizationServer);
        scopesSupported = List.copyOf(new LinkedHashSet<>(scopesSupported));
        scopesSupported.forEach(BearerConfig::requireScope);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects the settings; {@link #build()} checks them together. */
    public static final class Builder {

        private @Nullable URI resource;
        private List<URI> authorizationServers = List.of();
        private List<String> scopesSupported = List.of();
        private @Nullable BearerTokenVerifier verifier;

        private Builder() {}

        /**
         * Sets the canonical URI of this server, for example {@code https://mcp.example.com/mcp}.
         *
         * @param resource see {@link BearerConfig#resource()}
         * @return this builder
         */
        public Builder resource(URI resource) {
            this.resource = resource;
            return this;
        }

        /**
         * Sets the authorization servers clients may get tokens from.
         *
         * @param authorizationServers see {@link BearerConfig#authorizationServers()}; at least one
         * @return this builder
         */
        public Builder authorizationServers(URI... authorizationServers) {
            this.authorizationServers = List.of(authorizationServers);
            return this;
        }

        /**
         * Sets the scopes this server understands. Optional.
         *
         * @param scopesSupported see {@link BearerConfig#scopesSupported()}
         * @return this builder
         */
        public Builder scopesSupported(String... scopesSupported) {
            this.scopesSupported = List.of(scopesSupported);
            return this;
        }

        /**
         * Sets the verifier that checks each token.
         *
         * @param verifier see {@link BearerConfig#verifier()}
         * @return this builder
         */
        public Builder verifier(BearerTokenVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        /**
         * Checks the settings and builds the configuration.
         *
         * @return the configuration
         * @throws IllegalArgumentException if a setting is missing or breaks the rules of {@link
         *     BearerConfig}
         */
        public BearerConfig build() {
            if (resource == null) {
                throw new IllegalArgumentException("Bearer tokens need a resource");
            }
            if (verifier == null) {
                throw new IllegalArgumentException("Bearer tokens need a verifier");
            }
            return new BearerConfig(resource, authorizationServers, scopesSupported, verifier);
        }
    }

    private static void requireResource(URI resource) {
        requireHttpsOrLoopback(resource, "The resource");
        if (resource.getRawFragment() != null) {
            throw new IllegalArgumentException("The resource must have no fragment: " + resource);
        }
        if (resource.getRawQuery() != null) {
            throw new IllegalArgumentException("The resource must have no query (RFC 8707 §2): " + resource);
        }
        if (resource.getRawPath() != null && resource.getRawPath().endsWith("/")) {
            throw new IllegalArgumentException("The resource must have no trailing slash: " + resource);
        }
    }

    private static void requireAuthorizationServer(URI authorizationServer) {
        requireHttpsOrLoopback(authorizationServer, "An authorization server");
        if (authorizationServer.getRawFragment() != null || authorizationServer.getRawQuery() != null) {
            throw new IllegalArgumentException(
                    "An authorization server has no query and no fragment (RFC 8414 §2): " + authorizationServer);
        }
    }

    private static void requireHttpsOrLoopback(URI uri, String what) {
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException(what + " must be an absolute URI with a host: " + uri);
        }
        final var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && Origins.isLoopbackHost(uri.getHost()))) {
            throw new IllegalArgumentException(
                    what + " must be an https URI, or http only for a loopback host: " + uri);
        }
    }

    private static void requireScope(String scope) {
        if (scope.equals(OFFLINE_ACCESS)) {
            throw new IllegalArgumentException(
                    "A resource server does not advertise offline_access (MCP authorization § Refresh Tokens)");
        }
        ScopeTokens.require(scope);
    }
}
