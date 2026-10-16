/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.core.server.security.BearerTokenVerifier;
import dev.tachyonmcp.core.server.security.VerifiedToken;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MCP authorization (2025-11-25, 2026-07-28): § Canonical Server URI for the resource, § Authorization
 * Server Discovery for at least one authorization server (RFC 9728 §2), RFC 8414 §2 for the issuer
 * form, RFC 6749 §3.3 for scopes and § Refresh Tokens for {@code offline_access}. Every rule is
 * checked when the configuration is built, so a bearer server that cannot work cannot exist.
 */
class BearerConfigTest {

    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");
    private static final URI AUTHORIZATION_SERVER = URI.create("https://auth.example.com");
    private static final BearerTokenVerifier VERIFIER =
            token -> new VerifiedToken(() -> "alice", Set.of(), Instant.MAX, Set.of(RESOURCE));

    private static BearerConfig.Builder valid() {
        return BearerConfig.builder()
                .resource(RESOURCE)
                .authorizationServers(AUTHORIZATION_SERVER)
                .verifier(VERIFIER);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://mcp.example.com",
                "https://mcp.example.com/mcp",
                "https://mcp.example.com:8443/server/mcp",
                "HTTPS://MCP.EXAMPLE.COM/mcp",
                "http://localhost:8080/mcp",
                "http://127.0.0.1:8080",
                "http://127.1.2.3/mcp",
                "http://localhost./mcp",
                "http://[::1]:8080/mcp",
                "http://[0:0:0:0:0:0:0:1]/mcp"
            })
    void aCanonicalResourceIsAccepted(String resource) {
        final var config = valid().resource(URI.create(resource)).build();

        assertThat(config.resource()).isEqualTo(URI.create(resource));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/mcp",
                "mcp.example.com",
                "https:/mcp",
                "urn:example:mcp",
                "https://mcp.example.com/mcp#section",
                "https://mcp.example.com/mcp?tenant=1",
                "https://mcp.example.com/",
                "https://mcp.example.com/mcp/",
                "http://mcp.example.com/mcp",
                "http://10.0.0.5/mcp",
                "http://127.999.0.1/mcp",
                "http://localhost.example.com/mcp",
                "ftp://mcp.example.com/mcp"
            })
    void aResourceThatIsNotCanonicalIsRefused(String resource) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> valid().resource(URI.create(resource)).build());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://auth.example.com",
                "https://auth.example.com/",
                "https://auth.example.com:8443/tenant/one",
                "http://localhost:9000/realms/mcp",
                "http://[::1]:9000"
            })
    void anIssuerIsAcceptedAsRfc8414DefinesIt(String issuer) {
        final var config = valid().authorizationServers(URI.create(issuer)).build();

        assertThat(config.authorizationServers()).containsExactly(URI.create(issuer));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/realms/mcp",
                "auth.example.com",
                "http://auth.example.com",
                "http://10.0.0.5:9000",
                "https://auth.example.com/?tenant=1",
                "https://auth.example.com#section",
                "urn:example:issuer"
            })
    void anAuthorizationServerThatIsNotAnIssuerIsRefused(String issuer) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> valid().authorizationServers(URI.create(issuer)).build());
    }

    @Test
    void aBearerServerNeedsAnAuthorizationServer() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BearerConfig.builder()
                        .resource(RESOURCE)
                        .verifier(VERIFIER)
                        .build())
                .withMessageContaining("authorization server");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> valid().authorizationServers().build());
    }

    @Test
    void aBearerServerNeedsAResourceAndAVerifier() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BearerConfig.builder()
                        .authorizationServers(AUTHORIZATION_SERVER)
                        .verifier(VERIFIER)
                        .build())
                .withMessageContaining("resource");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BearerConfig.builder()
                        .resource(RESOURCE)
                        .authorizationServers(AUTHORIZATION_SERVER)
                        .build())
                .withMessageContaining("verifier");
    }

    @Test
    void severalAuthorizationServersAndScopesKeepTheirOrderAndDropRepeats() {
        final var second = URI.create("https://other-auth.example.com");

        final var config = valid().authorizationServers(AUTHORIZATION_SERVER, second, AUTHORIZATION_SERVER)
                .scopesSupported("tools:call", "tools:read", "tools:call")
                .build();

        assertThat(config.authorizationServers()).containsExactly(AUTHORIZATION_SERVER, second);
        assertThat(config.scopesSupported()).containsExactly("tools:call", "tools:read");
        assertThatThrownBy(() -> config.scopesSupported().add("more"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scopesAreOptional() {
        assertThat(valid().build().scopesSupported()).isEmpty();
    }

    @Test
    void aServerDoesNotAdvertiseOfflineAccess() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        valid().scopesSupported("tools:read", "offline_access").build())
                .withMessageContaining("offline_access");
    }

    /** RFC 6749 §3.3: {@code scope-token = 1*( %x21 / %x23-5B / %x5D-7E )}. */
    @ParameterizedTest
    @ValueSource(strings = {"", "two words", "quote\"d", "back\\slash", "tab\tbed", "café", "line\nbreak"})
    void aScopeThatIsNotAScopeTokenIsRefused(String scope) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> valid().scopesSupported(scope).build());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "tools:call",
                "files.read",
                "https://example.com/scope/read",
                "a~b_c-d",
                "!#$%&'()*+,/;<=>?@[]^`{|}"
            })
    void aScopeTokenIsAccepted(String scope) {
        assertThat(valid().scopesSupported(scope).build().scopesSupported()).containsExactly(scope);
    }
}
