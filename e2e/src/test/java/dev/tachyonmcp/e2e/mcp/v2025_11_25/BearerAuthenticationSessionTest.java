/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stateful sessions under bearer authentication. MCP security best practices § Session Hijacking:
 * servers that implement authorization MUST verify all inbound requests and MUST NOT use sessions
 * for authentication. The session records who created it; each request still carries its own token.
 */
class BearerAuthenticationSessionTest extends AbstractStatefulMcpE2eTest {

    // The E2E base shares one instance per class: a fresh verifier per test keeps recordings apart.
    private TestBearerTokens tokens = new TestBearerTokens();

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        startServer(b -> b.capabilities(c -> c.tools()).security(s -> s.bearerToken(tokens)), tokens::registerWhoAmI);
    }

    private HttpResponse<String> post(HttpClient http, @Nullable String token, @Nullable String sessionId, String body)
            throws Exception {
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (sessionId != null) {
            builder.header("MCP-Session-Id", sessionId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String initialize(HttpClient http, String token) throws Exception {
        final var init = post(
                http,
                token,
                null,
                // language=JSON
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25","capabilities":{},
                           "clientInfo":{"name":"test","version":"1.0"}}}
                """);
        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
        final var sessionId = init.headers().firstValue("MCP-Session-Id").orElseThrow();
        final var initialized = post(http, token, sessionId, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        assertThat(initialized.statusCode()).isEqualTo(202);
        return sessionId;
    }

    private static String whoAmI(int id) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"whoami","arguments":{}}}
                """.formatted(id);
    }

    @Test
    void sessionRecordsItsCreator() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            final var sessionId = initialize(http, ALICE);

            final var owner = engine().getSession(sessionId).orElseThrow().securityContext();
            assertThat(owner.isAuthenticated()).isTrue();
            assertThat(owner.principal()).extracting(Principal::getName).isEqualTo("alice");
            assertThat(owner.scopes()).containsExactlyInAnyOrder("tools:read", "tools:call");
        }
    }

    @Test
    void sessionNeverStandsInForCredentials() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            final var sessionId = initialize(http, ALICE);

            final var withoutToken = post(http, null, sessionId, whoAmI(2));

            assertThat(withoutToken.statusCode()).isEqualTo(401);
            assertThat(withoutToken.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
            assertThat(tokens.toolCallers()).isEmpty();
        }
    }

    @Test
    void handlerSeesTheRequestCallerNotTheSessionCreator() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            final var sessionId = initialize(http, ALICE);

            final var alice = post(http, ALICE, sessionId, whoAmI(2));
            final var bob = post(http, BOB, sessionId, whoAmI(3));

            assertThat(alice).isSuccess().hasId(2).hasTextContent("alice [tools:call, tools:read]");
            // Binding the session to its creator (404 for bob) is the next slice; identity is per request already.
            assertThat(bob).isSuccess().hasId(3).hasTextContent("bob [tools:read]");
            assertThat(engine().getSession(sessionId)
                            .orElseThrow()
                            .securityContext()
                            .principal())
                    .extracting(Principal::getName)
                    .isEqualTo("alice");
        }
    }
}
