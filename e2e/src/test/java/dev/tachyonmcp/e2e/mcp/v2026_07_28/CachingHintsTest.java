/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MCP 2026-07-28 requires {@code ttlMs}/{@code cacheScope} caching hints (SEP-2549) on every
 * {@code resultType: "complete"} result from {@code tools/list}, {@code prompts/list},
 * {@code resources/list}, {@code resources/templates/list}, and {@code resources/read}.
 * Regression test for {@code v2026_07_28.codecs.McpResponseMapper} only overriding
 * {@code discoverResult} and falling through to the 2025-11-25 record shapes (no caching fields)
 * for every other list/read method.
 */
class CachingHintsTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @BeforeEach
    void registerFixtures() {
        startServer(b -> b.capabilities(c -> c.tools().resources().prompts()), s -> {
            s.resources()
                    .register(
                            ResourceDescriptor.of("hello", "hello://world", "Hello resource", "text/plain"),
                            (ctx, request) -> TextResourceContents.of(request.uri(), "Hello, World!", "text/plain"))
                    .registerTemplate(
                            builder -> builder.name("tmpl")
                                    .uriTemplate("test://tmpl/{id}")
                                    .description("d"),
                            (ctx, request) -> TextResourceContents.of(request.uri(), "x", "text/plain"));
            s.prompts().register(PromptDescriptor.of("greeting", "A greeting"), java.util.List.of());
        });
    }

    private void assertCachingHints(String jsonBody) {
        assertThatJson(jsonBody).inPath("$.result.ttlMs").isEqualTo(0);
        assertThatJson(jsonBody).inPath("$.result.cacheScope").isEqualTo("public");
    }

    @Test
    @DisplayName("[SEP-2549] tools/list carries ttlMs and cacheScope")
    void toolsListIncludesCachingHints() throws Exception {
        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertCachingHints(response.body());
        }
    }

    @Test
    @DisplayName("[SEP-2549] prompts/list carries ttlMs and cacheScope")
    void promptsListIncludesCachingHints() throws Exception {
        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc": "2.0", "id": 2, "method": "prompts/list"}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertCachingHints(response.body());
        }
    }

    @Test
    @DisplayName("[SEP-2549] resources/list carries ttlMs and cacheScope")
    void resourcesListIncludesCachingHints() throws Exception {
        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc": "2.0", "id": 3, "method": "resources/list"}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertCachingHints(response.body());
        }
    }

    @Test
    @DisplayName("[SEP-2549] resources/templates/list carries ttlMs and cacheScope")
    void resourceTemplatesListIncludesCachingHints() throws Exception {
        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc": "2.0", "id": 4, "method": "resources/templates/list"}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertCachingHints(response.body());
        }
    }

    @Test
    @DisplayName("[SEP-2549] resources/read carries ttlMs and cacheScope")
    void resourceReadIncludesCachingHints() throws Exception {
        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc": "2.0", "id": 5, "method": "resources/read", "params": {"uri": "hello://world"}}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertCachingHints(response.body());
        }
    }
}
