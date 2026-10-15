/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.v2025_11_25.RawSessionClient.whoAmI;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.security.BearerTokenAuthenticationProvider;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A server whose provider lets a request without credentials through as anonymous, so an anonymous
 * session can exist next to authenticated ones. A session binds to its creator in both directions:
 * an anonymous creator's session rejects an authenticated caller, and the reverse.
 */
class MixedAuthenticationSessionTest extends AbstractStatefulMcpE2eTest {

    private static final String UNKNOWN_SESSION = "Unknown session";

    private TestBearerTokens tokens = new TestBearerTokens();

    @BeforeEach
    void startMixedServer() {
        tokens = new TestBearerTokens();
        final var bearer = new BearerTokenAuthenticationProvider(tokens);
        startServer(
                b -> b.capabilities(c -> c.tools())
                        .security(s -> s.authenticationProvider(
                                (HttpRequest request) -> request.headers().contains(HttpHeaderNames.AUTHORIZATION)
                                        ? bearer.authenticate(request)
                                        : SecurityContext.anonymous())),
                tokens::registerWhoAmI);
    }

    @Test
    void anonymousSessionRejectsAnAuthenticatedCaller() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(null);

            final var alicePost = client.post(ALICE, sessionId, whoAmI(2));
            final var aliceListen = client.get(ALICE, sessionId, null);
            final var aliceDelete = client.delete(ALICE, sessionId);
            final var anonymous = client.post(null, sessionId, whoAmI(3));

            assertThat(alicePost.statusCode()).isEqualTo(404);
            assertThat(alicePost.body()).isEqualTo(UNKNOWN_SESSION);
            assertThat(aliceListen.statusCode()).isEqualTo(404);
            assertThat(aliceListen.body()).containsExactly(UNKNOWN_SESSION);
            assertThat(aliceDelete.statusCode()).isEqualTo(404);
            assertThat(aliceDelete.body()).isEqualTo(UNKNOWN_SESSION);
            assertThat(anonymous).isSuccess().hasId(3).hasTextContent("anonymous");
            assertThat(tokens.toolCallers()).containsExactly("anonymous");
        }
    }

    @Test
    void authenticatedSessionRejectsAnAnonymousCaller() throws Exception {
        try (var client = new RawSessionClient(port)) {
            final var sessionId = client.initialize(ALICE);

            final var anonymousPost = client.post(null, sessionId, whoAmI(2));
            final var anonymousListen = client.get(null, sessionId, null);
            final var anonymousDelete = client.delete(null, sessionId);
            final var alice = client.post(ALICE, sessionId, whoAmI(3));

            assertThat(anonymousPost.statusCode()).isEqualTo(404);
            assertThat(anonymousPost.body()).isEqualTo(UNKNOWN_SESSION);
            assertThat(anonymousListen.statusCode()).isEqualTo(404);
            assertThat(anonymousListen.body()).containsExactly(UNKNOWN_SESSION);
            assertThat(anonymousDelete.statusCode()).isEqualTo(404);
            assertThat(anonymousDelete.body()).isEqualTo(UNKNOWN_SESSION);
            assertThat(alice).isSuccess().hasId(3).hasTextContent("alice [tools:call, tools:read]");
            assertThat(tokens.toolCallers()).containsExactly("alice [tools:call, tools:read]");
        }
    }
}
