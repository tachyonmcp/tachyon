/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.EXPIRED;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.NO_AUDIENCE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.SHORT_LIVED;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.WRONG_AUDIENCE;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.e2e.mcp.SseLines;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MCP authorization (2026-07-28) § Token Handling: a server MUST validate that a token was issued for
 * it as the audience (RFC 8707), and an invalid or expired token is {@code 401} (§ Error Handling).
 * Tachyon makes both checks, so a verifier that reports a token for another server, or one that has
 * expired, cannot let it through. The spec is silent on a stream that outlives its token (L4): Tachyon
 * closes the {@code subscriptions/listen} stream, or a streamed response, at the expiry, and the
 * client listens again with a fresh token.
 */
class TokenLifetimeTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    private static final String STREAMING_TOOL = "stream-then-wait";

    // language=JSON
    private static final String LISTEN = """
            {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
             "params":{"notifications":{"toolsListChanged":true}}}
            """;

    // language=JSON
    private static final String STREAMING_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":{}}}
            """.formatted(STREAMING_TOOL);

    // The E2E base shares one instance per class, so everything a test observes is made afresh for it.
    private TestBearerTokens tokens = new TestBearerTokens();
    private CountDownLatch toolStarted = new CountDownLatch(1);
    private CountDownLatch finishTool = new CountDownLatch(1);
    private CountDownLatch toolEnded = new CountDownLatch(1);
    private CountDownLatch undeliverable = new CountDownLatch(1);
    private AtomicBoolean toolInterrupted = new AtomicBoolean();
    private AtomicBoolean toolCompleted = new AtomicBoolean();

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
        toolStarted = new CountDownLatch(1);
        finishTool = new CountDownLatch(1);
        toolEnded = new CountDownLatch(1);
        undeliverable = new CountDownLatch(1);
        toolInterrupted = new AtomicBoolean();
        toolCompleted = new AtomicBoolean();
        final var started = toolStarted;
        final var finish = finishTool;
        final var ended = toolEnded;
        final var gone = undeliverable;
        final var interrupted = toolInterrupted;
        final var completed = toolCompleted;
        startServer(b -> b.capabilities(c -> c.tools()).security(TestBearerTokens.secured(tokens)), server -> {
            tokens.registerWhoAmI(server);
            server.tools().register(d -> d.name(STREAMING_TOOL), (ctx, request) -> {
                final var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
                if (stream != null) {
                    stream.start();
                }
                ctx.responseUndeliverable().thenRun(gone::countDown);
                started.countDown();
                try {
                    completed.set(finish.await(10, SECONDS));
                    return ToolResult.text("done");
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return ToolResult.text("interrupted");
                } finally {
                    ended.countDown();
                }
            });
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {WRONG_AUDIENCE, NO_AUDIENCE, EXPIRED})
    void aTokenNotForThisServerOrAlreadyExpiredIsUnauthorizedAndNothingRuns(String token) throws Exception {
        final var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", TestBearerTokens.WHOAMI)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"whoami","arguments":{},
                         "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                                  "io.modelcontextprotocol/clientInfo":{"name":"test","version":"1.0"},
                                  "io.modelcontextprotocol/clientCapabilities":{}}}}
                        """))
                .build();
        try (var http = HttpClient.newHttpClient()) {
            final var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().allValues("WWW-Authenticate"))
                    .containsExactly(TestBearerTokens.INVALID_TOKEN_CHALLENGE);
            assertThat(tokens.toolCallers()).as("the handler never ran").isEmpty();
        }
    }

    @Test
    void theListenStreamClosesAtTheExpiryAndTheClientListensAgainWithAFreshToken() throws Exception {
        try (var client = new Mcp20260728Client(port, SHORT_LIVED);
                var first = new SseLines(client.sendStreamingRequest(null, LISTEN))) {
            final var expiresAt = tokens.lastShortLivedExpiry();
            await().atMost(ofSeconds(5)).until(() -> first.contains("notifications/subscriptions/acknowledged"));

            await().atMost(ofSeconds(10)).until(first::ended);

            assertThat(first.endedAt())
                    .as("the stream is never closed before its token expires")
                    .isAfterOrEqualTo(expiresAt);
            // Subscriptions § Graceful Closure: a server that ends a subscription answers the listen
            // request with a successful result before closing the stream, not with a bare drop.
            assertThat(first.contains("\"id\":1") && first.contains("\"resultType\":\"complete\""))
                    .as("the stream ends with the graceful listen result: %s", first)
                    .isTrue();
            try (var again = new SseLines(client.sendStreamingRequest(null, LISTEN))) {
                await().atMost(ofSeconds(5)).until(() -> again.contains("notifications/subscriptions/acknowledged"));
                assertThat(again.ended())
                        .as("a fresh token listens again: %s", again)
                        .isFalse();
            }
        }
    }

    @Test
    void aStreamedResponseClosesAtTheExpiryAndTheToolIsNotCancelled() throws Exception {
        try (var client = new Mcp20260728Client(port, SHORT_LIVED);
                var response = new SseLines(client.sendStreamingRequest(null, STREAMING_CALL))) {
            final var expiresAt = tokens.lastShortLivedExpiry();
            assertThat(toolStarted.await(5, SECONDS)).isTrue();

            await().atMost(ofSeconds(10)).until(response::ended);

            assertThat(response.endedAt()).isAfterOrEqualTo(expiresAt);
            assertThat(undeliverable.await(5, SECONDS))
                    .as("with no session to resume, the closed connection makes the response undeliverable")
                    .isTrue();
            assertThat(toolInterrupted)
                    .as("closing the stream is not cancelling the call")
                    .isFalse();
            finishTool.countDown();
            assertThat(toolEnded.await(5, SECONDS)).isTrue();
            assertThat(toolCompleted).as("the tool ran to completion").isTrue();
            assertThat(toolInterrupted).isFalse();
        }
    }

    @Test
    void aListenStreamOfACallerWhoseTokenLastsStaysOpenAfterAnotherCallersExpired() throws Exception {
        try (var shortClient = new Mcp20260728Client(port, SHORT_LIVED);
                var longClient = new Mcp20260728Client(port, ALICE);
                var shortLived = new SseLines(shortClient.sendStreamingRequest(null, LISTEN));
                var lasting = new SseLines(longClient.sendStreamingRequest(null, LISTEN))) {
            await().atMost(ofSeconds(5)).until(() -> lasting.contains("notifications/subscriptions/acknowledged"));

            await().atMost(ofSeconds(8)).until(shortLived::ended);

            assertThat(lasting.ended())
                    .as("an hour-long token has not expired: %s", lasting)
                    .isFalse();
        }
    }
}
