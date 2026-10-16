/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.e2e.mcp.v2025_11_25.RawSessionClient.whoAmI;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.core.server.security.Owner;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Stateful sessions under bearer authentication. MCP security best practices § Session Hijacking:
 * servers that implement authorization MUST verify all inbound requests and MUST NOT use sessions
 * for authentication; sessions SHOULD be bound to user-specific information. Streamable HTTP §
 * Session Management: an unknown session answers 404. The session records who created it; each
 * request still carries its own token, and another caller's token reaches nothing of the session.
 */
class BearerAuthenticationSessionTest extends AbstractStatefulMcpE2eTest {

    private static final String UNKNOWN_SESSION = "Unknown session";

    // The E2E base shares one instance per class: a fresh verifier per test keeps recordings apart.
    private TestBearerTokens tokens = new TestBearerTokens();

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::tools).security(TestBearerTokens.secured(tokens)),
                tokens::registerWhoAmI);
    }

    @Test
    void sessionRecordsItsCreator() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);

            // K13: the owner is issuer|name, so "alice" from another issuer could not borrow this session.
            assertThat(engine().getSession(sessionId).orElseThrow().owner())
                    .isEqualTo(new Owner.Named(TestBearerTokens.AUTHORIZATION_SERVER + "|alice"));
        }
    }

    @Test
    void sessionNeverStandsInForCredentials() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);

            final var withoutToken = client.post(null, sessionId, whoAmI(2));

            assertThat(withoutToken.statusCode()).isEqualTo(401);
            assertThat(withoutToken.headers().allValues("WWW-Authenticate"))
                    .containsExactly(TestBearerTokens.CHALLENGE);
            assertThat(tokens.toolCallers()).isEmpty();
        }
    }

    @Test
    void anotherCallerCannotUseTheSessionOfItsCreator() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);
            final var unknown = client.post(BOB, "sess_unknown", whoAmI(3));

            final var bob = client.post(BOB, sessionId, whoAmI(3));
            final var alice = client.post(ALICE, sessionId, whoAmI(2));

            assertThat(unknown.statusCode()).isEqualTo(404);
            assertThat(bob.statusCode()).isEqualTo(unknown.statusCode());
            assertThat(bob.body()).isEqualTo(UNKNOWN_SESSION).isEqualTo(unknown.body());
            assertThat(bob.headers().firstValue("Content-Type"))
                    .isEqualTo(unknown.headers().firstValue("Content-Type"));
            assertThat(alice).isSuccess().hasId(2).hasTextContent("alice [tools:call, tools:read]");
            assertThat(tokens.toolCallers()).containsExactly("alice [tools:call, tools:read]");
        }
    }

    @Test
    void anotherCallerCannotTerminateTheSessionOfItsCreator() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);

            final var bob = client.delete(BOB, sessionId);
            final var aliceAfterBob = client.post(ALICE, sessionId, whoAmI(2));
            final var aliceDelete = client.delete(ALICE, sessionId);
            final var aliceAfterDelete = client.post(ALICE, sessionId, whoAmI(3));

            assertThat(bob.statusCode()).isEqualTo(404);
            assertThat(bob.body()).isEqualTo(UNKNOWN_SESSION);
            assertThat(aliceAfterBob).isSuccess().hasId(2);
            assertThat(aliceDelete.statusCode()).isEqualTo(200);
            assertThat(aliceAfterDelete.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void anotherCallerCannotListenToOrResumeTheSessionOfItsCreator() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);

            final var listen = client.get(BOB, sessionId, null);
            final var resume = client.get(BOB, sessionId, "0");

            assertThat(listen.statusCode()).isEqualTo(404);
            assertThat(listen.body()).containsExactly(UNKNOWN_SESSION);
            assertThat(resume.statusCode()).isEqualTo(404);
            assertThat(resume.body()).containsExactly(UNKNOWN_SESSION);
        }
    }

    @Test
    void streamOfTheCreatorSurvivesAnotherCallersAttempt() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);
            final var session = engine().getSession(sessionId).orElseThrow();
            final var stream = client.get(ALICE, sessionId, null);
            assertThat(stream.statusCode()).isEqualTo(200);
            await().atMost(ofSeconds(5)).until(() -> session.connection() != SseConnection.noop());

            final var bob = client.get(BOB, sessionId, null);
            session.send(new SseEvent("1", "message", "alice-still-listening"));

            try (var lines = stream.body()) {
                final var received = CompletableFuture.supplyAsync(
                                () -> lines.filter(line -> line.contains("alice-still-listening"))
                                        .findFirst())
                        .orTimeout(5, SECONDS)
                        .get();
                assertThat(bob.statusCode()).isEqualTo(404);
                assertThat(received).isPresent();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // language=JSON
                """
                {"jsonrpc":"2.0","id":"srv-1","result":{}}
                """,
                // language=JSON
                """
                {"jsonrpc":"2.0","id":"srv-1","error":{"code":-32000,"message":"declined"}}
                """,
                // language=JSON
                """
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}
                """
            })
    void anotherCallerCannotAnswerOrCancelOnBehalfOfTheCreator(String message) throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);

            final var bob = client.post(BOB, sessionId, message);
            final var alice = client.post(ALICE, sessionId, message);

            assertThat(bob.statusCode()).isEqualTo(404);
            assertThat(bob.body()).isEqualTo(UNKNOWN_SESSION);
            assertThat(alice.statusCode()).isEqualTo(202);
        }
    }
}
