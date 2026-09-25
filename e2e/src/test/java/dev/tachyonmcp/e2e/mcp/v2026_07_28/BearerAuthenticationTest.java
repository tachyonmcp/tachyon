/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.UNVERIFIABLE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.WHOAMI;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Bearer authentication on Streamable HTTP. MCP authorization (2026-07-28) § Access Token Usage:
 * the token travels in {@code Authorization: Bearer} on every request, never in the query string;
 * invalid tokens get {@code 401}. RFC 6750 §3.1 shapes the challenge.
 */
class BearerAuthenticationTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    // language=JSON
    private static final String WHOAMI_CALL = """
            {
              "jsonrpc":"2.0",
              "id":1,
              "method":"tools/call",
              "params":{
                "name":"whoami",
                "arguments":{},
                "_meta":{
                  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                  "io.modelcontextprotocol/clientInfo":{"name":"test","version":"1.0"},
                  "io.modelcontextprotocol/clientCapabilities":{}
                }
              }
            }
            """;

    private static final Map<String, String> WHOAMI_HEADERS = Map.of("Mcp-Method", "tools/call", "Mcp-Name", WHOAMI);

    // The E2E base shares one instance per class: a fresh verifier per test keeps recordings apart.
    private TestBearerTokens tokens = new TestBearerTokens();

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        startServer(b -> b.capabilities(c -> c.tools()).security(s -> s.bearerToken(tokens)), tokens::registerWhoAmI);
    }

    private HttpResponse<String> callWhoAmI(String... authorizations) throws Exception {
        return callWhoAmIAt("/mcp", authorizations);
    }

    private HttpResponse<String> callWhoAmIAt(String pathAndQuery, String... authorizations) throws Exception {
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + pathAndQuery))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .POST(HttpRequest.BodyPublishers.ofString(WHOAMI_CALL));
        WHOAMI_HEADERS.forEach(builder::header);
        for (final var authorization : authorizations) {
            builder.header("Authorization", authorization);
        }
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertRejected(HttpResponse<String> response, int status, String challenge) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(challenge);
        assertThat(response.body()).isEmpty();
        assertThat(tokens.toolCallers()).as("the tool must not have run").isEmpty();
    }

    @Test
    void handlerSeesTheAuthenticatedCaller() throws Exception {
        final var response = callWhoAmI("Bearer " + ALICE);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response).isSuccess().hasId(1).hasTextContent("alice [tools:call, tools:read]");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void verifierRunsOnVirtualThreadsOffTheEventLoop() throws Exception {
        callWhoAmI("Bearer " + ALICE);
        callWhoAmI("Bearer " + BOB);

        assertThat(tokens.verifierThreads())
                .hasSize(2)
                .allSatisfy(thread -> assertThat(thread.isVirtual())
                        .as("verifier thread %s", thread)
                        .isTrue());
    }

    @Test
    void identityBelongsToEachRequest() throws Exception {
        // One HttpClient keeps the connection alive: a pooled connection must not carry identity.
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .POST(HttpRequest.BodyPublishers.ofString(WHOAMI_CALL));
        WHOAMI_HEADERS.forEach(builder::header);
        try (var http = HttpClient.newHttpClient()) {
            final var alice = http.send(
                    builder.copy().header("Authorization", "Bearer " + ALICE).build(),
                    HttpResponse.BodyHandlers.ofString());
            final var bob = http.send(
                    builder.copy().header("Authorization", "Bearer " + BOB).build(),
                    HttpResponse.BodyHandlers.ofString());
            final var anonymous = http.send(builder.copy().build(), HttpResponse.BodyHandlers.ofString());

            assertThat(alice).isSuccess().hasTextContent("alice [tools:call, tools:read]");
            assertThat(bob).isSuccess().hasTextContent("bob [tools:read]");
            assertThat(anonymous.statusCode()).isEqualTo(401);
        }
        assertThat(tokens.toolCallers())
                .containsExactlyInAnyOrder("alice [tools:call, tools:read]", "bob [tools:read]");
    }

    @Test
    void missingTokenGetsBareChallenge() throws Exception {
        // RFC 6750 §3.1: no error code when the request carries no authentication information.
        assertRejected(callWhoAmI(), 401, "Bearer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer unknown-token", "bearer unknown-token", "Bearer not a token", "Bearer"})
    void invalidTokenIsUnauthorized(String authorization) throws Exception {
        assertRejected(callWhoAmI(authorization), 401, "Bearer error=\"invalid_token\"");
    }

    @Test
    void otherSchemeCarriesNoBearerCredentials() throws Exception {
        // A 401 challenge, not a 400, so the client can start the OAuth flow the challenge names.
        assertRejected(callWhoAmI("Basic YWxpY2U6c2VjcmV0"), 401, "Bearer");
    }

    @Test
    void severalAuthorizationHeadersAreBadRequest() throws Exception {
        assertRejected(callWhoAmI("Bearer " + ALICE, "Bearer " + BOB), 400, "Bearer error=\"invalid_request\"");
    }

    @Test
    void tokenInQueryStringIsRefusedEvenWithValidHeader() throws Exception {
        // MCP authorization § Token Requirements: access tokens MUST NOT be in the query string.
        assertRejected(
                callWhoAmIAt("/mcp?access_token=" + ALICE, "Bearer " + ALICE), 400, "Bearer error=\"invalid_request\"");
    }

    @Test
    void verifierOutageIsServerErrorWithoutChallenge() throws Exception {
        final var response = callWhoAmI("Bearer " + UNVERIFIABLE);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().allValues("WWW-Authenticate")).isEqualTo(List.of());
        assertThat(tokens.toolCallers()).isEmpty();
    }
}
