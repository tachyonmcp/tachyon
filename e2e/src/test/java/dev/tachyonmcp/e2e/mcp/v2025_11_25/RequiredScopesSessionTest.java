/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.METADATA_URL;
import static dev.tachyonmcp.e2e.mcp.v2025_11_25.RawSessionClient.whoAmI;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.e2e.mcp.ScopedTools;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Required scopes inside a 2025-11-25 session. The session belongs to its creator but never stands in
 * for scopes: each request is checked on its own, so a refusal leaves the session usable, and a caller
 * with more scopes than the creator cannot use the session at all.
 */
class RequiredScopesSessionTest extends AbstractStatefulMcpE2eTest {

    private TestBearerTokens tokens = new TestBearerTokens();
    private ScopedTools scoped = new ScopedTools();

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        scoped = new ScopedTools();
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools).security(TestBearerTokens.secured(tokens)),
                server -> {
                    tokens.registerWhoAmI(server);
                    scoped.register(server);
                });
    }

    private static String call(String tool) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                """.formatted(tool);
    }

    @Test
    void aRefusedCallLeavesTheSessionUsable() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(BOB);

            final var refused = client.post(BOB, sessionId, call(ScopedTools.WRITE));
            final var next = client.post(BOB, sessionId, whoAmI(3));

            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(refused.headers().allValues("WWW-Authenticate"))
                    .containsExactly("Bearer error=\"insufficient_scope\", scope=\"tools:call\", "
                            + "resource_metadata=\"" + METADATA_URL + "\"");
            assertThat(refused.body()).isEmpty();
            assertThat(next.statusCode()).isEqualTo(200);
            assertThat(scoped.ran()).isEmpty();
        }
    }

    @Test
    void aCallerWithTheScopeIsServedInItsOwnSession() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);

            final var served = client.post(ALICE, sessionId, call(ScopedTools.WRITE));

            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).contains("written");
            assertThat(scoped.ran()).containsExactly(ScopedTools.WRITE);
        }
    }

    @Test
    void theSessionOfALessPrivilegedCreatorIsNotAWayAround() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(BOB);

            final var alice = client.post(ALICE, sessionId, call(ScopedTools.WRITE));

            assertThat(alice.statusCode())
                    .as("another caller's session is unknown to Alice")
                    .isEqualTo(404);
            assertThat(scoped.ran()).isEmpty();
        }
    }
}
