/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.e2e.mcp.HeadAuthentication;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MCP authorization (2025-11-25) § Token Requirements: authorization MUST be included in every HTTP
 * request and the server MUST validate it; § Error Handling: a missing or invalid token is
 * {@code 401}. Authentication runs on the request head, before the body is requested or read, before
 * any protocol check can tell an anonymous caller what the server supports, and within a bounded
 * time. Origin validation still comes first and a preflight needs no token.
 */
@Timeout(30)
class HeadAuthenticationTest extends AbstractStatefulMcpE2eTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    private TestBearerTokens tokens = new TestBearerTokens();

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools).security(TestBearerTokens.secured(tokens)),
                tokens::registerWhoAmI);
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    @Test
    void aRequestWithoutATokenIsRefusedBeforeItsBodyIsRequested() throws Exception {
        final var refusal = HeadAuthentication.refusalBeforeTheBody(port, "2025-11-25");

        assertThat(refusal.status())
                .as("the first status line: neither 100 Continue nor 413")
                .isEqualTo(401);
        assertThat(refusal.challenge()).isEqualTo(TestBearerTokens.CHALLENGE);
        assertThat(refusal.closed())
                .as("the body was never read, so the connection ends")
                .isTrue();
    }

    @Test
    void anInvalidProtocolVersionDoesNotAnswerAnAnonymousCaller() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            final var anonymous = http.send(
                    post(PING).setHeader("MCP-Protocol-Version", "1999-01-01").build(),
                    HttpResponse.BodyHandlers.ofString());
            final var authenticated = http.send(
                    post(PING)
                            .setHeader("MCP-Protocol-Version", "1999-01-01")
                            .header("Authorization", "Bearer " + ALICE)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(anonymous.statusCode()).as(anonymous.body()).isEqualTo(401);
            assertThat(anonymous.body()).doesNotContain("supported");
            assertThat(authenticated.statusCode())
                    .as("with a token the version is checked as before")
                    .isEqualTo(400);
        }
    }

    @Test
    void aPreflightNeedsNoToken() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            final var preflight = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/mcp"))
                            .header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "POST")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(preflight.statusCode()).isIn(200, 204);
            assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
                    .isPresent();
            assertThat(preflight.headers().allValues("WWW-Authenticate")).isEmpty();
        }
    }

    @Test
    void aRefusalCarriesTheCorsHeaders() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            final var refused = http.send(
                    post(PING).header("Origin", "http://localhost:3000").build(), HttpResponse.BodyHandlers.ofString());

            assertThat(refused.statusCode()).isEqualTo(401);
            assertThat(refused.headers().firstValue("Access-Control-Allow-Origin"))
                    .isPresent();
        }
    }

    @Test
    void aCustomProviderAnswersWithTheChallengeOfItsOwnSchemeAndNeverBearer() throws Exception {
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools)
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .authenticationProvider(HeadAuthentication.apiKey())),
                tokens::registerWhoAmI);
        try (var http = HttpClient.newHttpClient()) {
            final var missing = http.send(post(PING).build(), HttpResponse.BodyHandlers.ofString());
            final var wrong =
                    http.send(post(PING).header("X-Api-Key", "nope").build(), HttpResponse.BodyHandlers.ofString());
            final var accepted = http.send(
                    post(PING).header("X-Api-Key", HeadAuthentication.API_KEY).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(missing.statusCode()).isEqualTo(401);
            assertThat(missing.headers().allValues("WWW-Authenticate")).containsExactly("ApiKey realm=\"mcp\"");
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(wrong.headers().allValues("WWW-Authenticate"))
                    .containsExactly("ApiKey realm=\"mcp\", error=\"invalid_key\"");
            assertThat(accepted.statusCode()).as("the key is accepted").isNotEqualTo(401);
            assertThat(accepted.headers().allValues("WWW-Authenticate")).isEmpty();
        }
    }

    @Test
    void aChallengeParameterIsEscapedAndCannotInjectAHeader() throws Exception {
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools)
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .authenticationProvider(HeadAuthentication.quotingInTheChallenge())),
                tokens::registerWhoAmI);
        final var quoted = HeadAuthentication.anonymousPost(port, "2025-11-25");
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools)
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .authenticationProvider(HeadAuthentication.injectingAHeader())),
                tokens::registerWhoAmI);

        final var injected = HeadAuthentication.anonymousPost(port, "2025-11-25");

        assertThat(quoted.status()).isEqualTo(401);
        assertThat(quoted.headers().get("www-authenticate")).isEqualTo("ApiKey realm=\"say \\\"hi\\\"\"");
        assertThat(injected.status())
                .as("a value that cannot be sent is a provider failure, not a response")
                .isEqualTo(500);
        assertThat(injected.headers()).doesNotContainKeys("x-injected", "www-authenticate");
    }

    @Test
    void aVerifierThatNeverAnswersIsCutOffWith503AndRetryAfter() throws Exception {
        final var release = new CountDownLatch(1);
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools)
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .bearerToken(TestBearerTokens.bearer(HeadAuthentication.hanging(release)))
                                .authenticationTimeout(Duration.ofMillis(300))),
                tokens::registerWhoAmI);
        try (var http = HttpClient.newHttpClient()) {
            final var started = System.nanoTime();

            final var response = http.send(
                    post(PING)
                            .header("Authorization", "Bearer " + ALICE)
                            .timeout(Duration.ofSeconds(10))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After").orElseThrow())
                    .matches("[1-9][0-9]*");
            assertThat(response.headers().allValues("WWW-Authenticate"))
                    .as("no challenge: the client keeps a token that may be valid")
                    .isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest(name = "expect-continue={0}")
    @ValueSource(booleans = {false, true})
    void aBodyLargerThanOneReadSurvivesAuthentication(boolean expectContinue) throws Exception {
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools)
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .bearerToken(TestBearerTokens.bearer(
                                        HeadAuthentication.delaying(tokens, Duration.ofMillis(200))))),
                HeadAuthentication::registerDigest);
        final var payload = HeadAuthentication.payload();
        try (var client = new RawSessionClient(port);
                var http = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()) {
            final var sessionId = client.initialize(ALICE);

            final var response = http.send(
                    post("""
                            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"digest","arguments":{"payload":"%s"}}}
                            """.formatted(payload))
                            .header("Authorization", "Bearer " + ALICE)
                            .header("MCP-Session-Id", sessionId)
                            .expectContinue(expectContinue)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains(HeadAuthentication.digestOf(payload));
        }
    }
}
