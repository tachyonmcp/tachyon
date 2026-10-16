/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.config.BearerConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RFC 9728 §3.1: the metadata of a resource with a path is served at the well-known segment inserted
 * between the host and that path, and §5.1 puts that URL in the challenge. MCP authorization §
 * Protected Resource Metadata Discovery Requirements: the document names at least one authorization
 * server.
 */
class ProtectedResourceMetadataTest {

    private static BearerConfig config(URI resource, String... scopes) {
        return BearerConfig.builder()
                .resource(resource)
                .authorizationServers(URI.create("https://auth.example.com"), URI.create("https://auth2.example.com/t"))
                .scopesSupported(scopes)
                .verifier(token -> new VerifiedToken(() -> "alice", Set.of(), Instant.MAX, Set.of(resource)))
                .build();
    }

    @ParameterizedTest
    @CsvSource({
        "https://mcp.example.com/mcp,/.well-known/oauth-protected-resource/mcp,https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
        "https://mcp.example.com,/.well-known/oauth-protected-resource,https://mcp.example.com/.well-known/oauth-protected-resource",
        "https://mcp.example.com:8443/a/b,/.well-known/oauth-protected-resource/a/b,https://mcp.example.com:8443/.well-known/oauth-protected-resource/a/b",
        "http://localhost:8080/mcp,/.well-known/oauth-protected-resource/mcp,http://localhost:8080/.well-known/oauth-protected-resource/mcp",
        "http://[::1]:8080/mcp,/.well-known/oauth-protected-resource/mcp,http://[::1]:8080/.well-known/oauth-protected-resource/mcp"
    })
    void insertsTheWellKnownSegmentBetweenTheHostAndThePath(String resource, String path, String url) {
        assertThat(ProtectedResourceMetadata.path(URI.create(resource))).isEqualTo(path);
        assertThat(ProtectedResourceMetadata.url(URI.create(resource))).isEqualTo(URI.create(url));
    }

    @Test
    void servesThePathInsertedAndTheRootWellKnownPathsAndNothingElse() {
        assertThat(ProtectedResourceMetadata.paths(URI.create("https://mcp.example.com/mcp")))
                .containsExactlyInAnyOrder(
                        "/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource");
        assertThat(ProtectedResourceMetadata.paths(URI.create("https://mcp.example.com")))
                .containsExactly("/.well-known/oauth-protected-resource");
    }

    @Test
    void theDocumentNamesTheResourceItsAuthorizationServersAndItsScopes() {
        final var json = ProtectedResourceMetadata.json(
                config(URI.create("https://mcp.example.com/mcp"), "tools:call", "tools:read"));

        // language=json
        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("""
                        {"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com","https://auth2.example.com/t"],"scopes_supported":["tools:call","tools:read"],"bearer_methods_supported":["header"]}""");
    }

    @Test
    void theDocumentOmitsScopesSupportedWhenThereAreNone() {
        final var json = ProtectedResourceMetadata.json(config(URI.create("https://mcp.example.com")));

        // language=json
        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("""
                        {"resource":"https://mcp.example.com","authorization_servers":["https://auth.example.com","https://auth2.example.com/t"],"bearer_methods_supported":["header"]}""");
    }
}
