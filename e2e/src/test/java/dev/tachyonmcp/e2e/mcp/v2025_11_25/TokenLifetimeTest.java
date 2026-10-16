/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.EXPIRED;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.NO_AUDIENCE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.SHORT_LIVED;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.WRONG_AUDIENCE;
import static dev.tachyonmcp.e2e.mcp.v2025_11_25.RawSessionClient.whoAmI;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.e2e.mcp.SseLines;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MCP authorization (2025-11-25) § Token Handling: a server MUST validate that a token was issued for
 * it as the audience (RFC 8707), and an invalid or expired token is {@code 401} (§ Error Handling).
 * Tachyon makes both checks, so a verifier that reports a token for another server, or one that has
 * expired, cannot let it through. The spec is silent on a stream that outlives its token (L4): Tachyon
 * closes it at the expiry, and the client resumes with a fresh token from its {@code Last-Event-ID}.
 */
class TokenLifetimeTest extends AbstractStatefulMcpE2eTest {

    private static final String STREAMING_TOOL = "stream-then-wait";

    // The E2E base shares one instance per class, so everything a test observes is made afresh for it.
    private TestBearerTokens tokens = new TestBearerTokens();
    private CountDownLatch toolStarted = new CountDownLatch(1);
    private CountDownLatch finishTool = new CountDownLatch(1);
    private CountDownLatch toolEnded = new CountDownLatch(1);
    private AtomicBoolean toolInterrupted = new AtomicBoolean();
    private AtomicBoolean toolCompleted = new AtomicBoolean();

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        toolStarted = new CountDownLatch(1);
        finishTool = new CountDownLatch(1);
        toolEnded = new CountDownLatch(1);
        toolInterrupted = new AtomicBoolean();
        toolCompleted = new AtomicBoolean();
        final var started = toolStarted;
        final var finish = finishTool;
        final var ended = toolEnded;
        final var interrupted = toolInterrupted;
        final var completed = toolCompleted;
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools).security(TestBearerTokens.secured(tokens)),
                server -> {
                    tokens.registerWhoAmI(server);
                    server.tools().register(d -> d.name(STREAMING_TOOL), (ctx, request) -> {
                        final var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
                        if (stream != null) {
                            stream.start();
                        }
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
        try (var client = new RawSessionClient(port)) {
            final var response = client.post(token, null, whoAmI(1));

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().allValues("WWW-Authenticate"))
                    .containsExactly(TestBearerTokens.INVALID_TOKEN_CHALLENGE);
            assertThat(tokens.toolCallers()).as("the handler never ran").isEmpty();
        }
    }

    @Test
    void theGetStreamClosesAtTheExpiryAndAResumeWithAFreshTokenLosesNothing() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(SHORT_LIVED);
            final var session = engine().getLocalSession(sessionId).orElseThrow();
            final var first =
                    new SseLines(client.listen(SHORT_LIVED, sessionId, null).get(5, SECONDS));
            final var expiresAt = tokens.lastShortLivedExpiry();
            await().atMost(ofSeconds(5)).until(() -> session.connection() != SseConnection.noop());
            engine().sendNotification(
                            session, "notifications/message", Map.of("level", "info", "data", "before-expiry"));
            await().atMost(ofSeconds(5)).until(() -> first.contains("before-expiry"));
            final var cursor = first.lastEventId();

            await().atMost(ofSeconds(10)).until(first::ended);

            assertThat(first.endedAt())
                    .as("the stream is never closed before its token expires")
                    .isAfterOrEqualTo(expiresAt);
            assertThat(first.contains("retry:"))
                    .as("closed gracefully, with a retry hint")
                    .isTrue();
            engine().sendNotification(session, "notifications/message", Map.of("level", "info", "data", "after-close"));
            try (var resumed =
                    new SseLines(client.listen(SHORT_LIVED, sessionId, cursor).get(5, SECONDS))) {
                await().atMost(ofSeconds(5)).until(() -> resumed.contains("after-close"));
                assertThat(resumed.contains("before-expiry"))
                        .as("nothing is delivered twice")
                        .isFalse();
                assertThat(resumed.ended())
                        .as("the fresh token opens a stream that is open again: %s", resumed)
                        .isFalse();
            }
        }
    }

    @Test
    void aStreamedResponseClosesAtTheExpiryAndTheToolIsNotCancelled() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(SHORT_LIVED);
            final var response =
                    new SseLines(client.postStreaming(SHORT_LIVED, sessionId, """
                            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                            """.formatted(STREAMING_TOOL))
                            .get(5, SECONDS));
            final var expiresAt = tokens.lastShortLivedExpiry();
            assertThat(toolStarted.await(5, SECONDS)).isTrue();

            await().atMost(ofSeconds(10)).until(response::ended);

            assertThat(response.endedAt()).isAfterOrEqualTo(expiresAt);
            assertThat(toolCompleted).as("the tool is still working").isFalse();
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
    void aStreamOfACallerWhoseTokenLastsStaysOpenAfterAnotherCallersExpired() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var shortSession = client.initialize(SHORT_LIVED);
            final var longSession = client.initialize(ALICE);
            try (var shortLived = new SseLines(
                            client.listen(SHORT_LIVED, shortSession, null).get(5, SECONDS));
                    var lasting =
                            new SseLines(client.listen(ALICE, longSession, null).get(5, SECONDS))) {
                final var session = engine().getLocalSession(longSession).orElseThrow();
                await().atMost(ofSeconds(5)).until(() -> session.connection() != SseConnection.noop());

                await().atMost(ofSeconds(8)).until(shortLived::ended);

                assertThat(Instant.now()).isAfterOrEqualTo(tokens.lastShortLivedExpiry());
                assertThat(lasting.ended())
                        .as("an hour-long token has not expired: %s", lasting)
                        .isFalse();
                engine().sendNotification(
                                session, "notifications/message", Map.of("level", "info", "data", "still-here"));
                await().atMost(ofSeconds(5)).until(() -> lasting.contains("still-here"));
            }
        }
    }
}
