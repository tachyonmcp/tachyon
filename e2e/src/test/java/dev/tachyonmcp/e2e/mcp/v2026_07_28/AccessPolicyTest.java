/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.ADMIN;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.ADMIN_NOTES;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.ADMIN_PROMPT;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.ADMIN_TEMPLATE;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.ADMIN_TOOL;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.PARTNER;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.PARTNER_TOOL;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.PUBLIC_NOTES;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.PUBLIC_PROMPT;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.PUBLIC_TOOL;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.READ_FILES;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.STRANGER;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.USER;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.WRITER;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationKind;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.e2e.mcp.GuardedFeatures;
import dev.tachyonmcp.e2e.mcp.GuardedFeatures.Sabotage;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.SseFrame;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An {@code AccessPolicy} hides features by caller: a hidden feature is neither listed nor reachable, on
 * every path, and every answer about it is the answer about a feature that does not exist, so it is no
 * oracle. Required scopes are something else: a step-up, so the tool stays listed and the call is {@code
 * 403} (MCP authorization § Scope Selection Strategy, § Runtime Insufficient Scope). An {@code
 * AuthenticationException} thrown while serving is a {@code 401} with the server's own challenge, or a
 * JSON-RPC error once the response has started streaming.
 */
@Timeout(30)
class AccessPolicyTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    private GuardedFeatures guarded = new GuardedFeatures();

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @BeforeEach
    void startGuardedServer() {
        guarded = new GuardedFeatures();
        startServer(guarded::configure, guarded::register);
    }

    private Mcp20260728Client as(String token) {
        return new Mcp20260728Client(port, token);
    }

    private static String call(String tool) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                """.formatted(tool);
    }

    private static String list(String method) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":1,"method":"%s","params":{}}
                """.formatted(method);
    }

    private static String read(String uri) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"%s"}}
                """.formatted(uri);
    }

    /** The answer about {@code hidden}, with its name swapped for {@code unknown}'s: what a probe can compare. */
    private static void assertAnswersLikeAnUnknown(
            HttpResponse<String> hidden, String hiddenName, HttpResponse<String> unknown, String unknownName) {
        assertThat(hidden.statusCode()).isEqualTo(unknown.statusCode());
        assertThat(hidden.body().replace(hiddenName, "<name>"))
                .as("a hidden feature answers exactly like one that does not exist")
                .isEqualTo(unknown.body().replace(unknownName, "<name>"));
    }

    @Test
    void aHiddenToolIsNeitherListedNorCallableAndAnswersLikeAnUnknownOne() throws Exception {
        try (var user = as(USER);
                var admin = as(ADMIN)) {
            final var userList = user.post(list("tools/list"));
            final var adminList = admin.post(list("tools/list"));

            assertThat(userList.body()).contains(PUBLIC_TOOL, READ_FILES).doesNotContain(ADMIN_TOOL, PARTNER_TOOL);
            assertThat(adminList.body()).contains(PUBLIC_TOOL, ADMIN_TOOL).doesNotContain(PARTNER_TOOL);
            assertAnswersLikeAnUnknown(
                    user.post(call(ADMIN_TOOL)), ADMIN_TOOL, user.post(call("no-such-tool")), "no-such-tool");
            assertThat(admin.post(call(ADMIN_TOOL)).body()).contains("ran " + ADMIN_TOOL);
            assertThat(guarded.ran()).containsExactly(ADMIN_TOOL);
        }
    }

    @Test
    void hiddenResourcesTemplatesAndPromptsAnswerLikeUnknownOnes() throws Exception {
        try (var user = as(USER);
                var admin = as(ADMIN)) {
            assertThat(user.post(list("resources/list")).body())
                    .contains(PUBLIC_NOTES)
                    .doesNotContain(ADMIN_NOTES);
            assertThat(admin.post(list("resources/list")).body()).contains(PUBLIC_NOTES, ADMIN_NOTES);
            assertThat(user.post(list("resources/templates/list")).body()).doesNotContain(ADMIN_TEMPLATE);
            assertThat(admin.post(list("resources/templates/list")).body()).contains(ADMIN_TEMPLATE);
            assertThat(user.post(list("prompts/list")).body())
                    .contains(PUBLIC_PROMPT)
                    .doesNotContain(ADMIN_PROMPT);

            assertAnswersLikeAnUnknown(
                    user.post(read(ADMIN_NOTES)), ADMIN_NOTES, user.post(read("notes://nothing")), "notes://nothing");
            assertAnswersLikeAnUnknown(
                    user.post(read("secret://7")), "secret://7", user.post(read("nothing://7")), "nothing://7");
            assertThat(admin.post(read("secret://7")).body()).contains("secret");
            assertAnswersLikeAnUnknown(
                    user.post("""
                            {"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"%s"}}
                            """.formatted(ADMIN_PROMPT)), ADMIN_PROMPT, user.post("""
                            {"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"no-prompt"}}
                            """), "no-prompt");
        }
    }

    /** Completing the arguments of a hidden prompt or template would reveal it: it completes with nothing. */
    @Test
    void completionsOfHiddenPromptsAndTemplatesAreEmptyLikeUnknownOnes() throws Exception {
        final var promptRef = """
                {"jsonrpc":"2.0","id":1,"method":"completion/complete","params":{
                  "ref":{"type":"ref/prompt","name":"%s"},"argument":{"name":"user","value":"r"}}}
                """;
        final var resourceRef = """
                {"jsonrpc":"2.0","id":1,"method":"completion/complete","params":{
                  "ref":{"type":"ref/resource","uri":"%s"},"argument":{"name":"id","value":"4"}}}
                """;
        try (var user = as(USER);
                var admin = as(ADMIN)) {
            assertAnswersLikeAnUnknown(
                    user.post(promptRef.formatted(ADMIN_PROMPT)),
                    ADMIN_PROMPT,
                    user.post(promptRef.formatted("no-prompt")),
                    "no-prompt");
            assertAnswersLikeAnUnknown(
                    user.post(resourceRef.formatted(ADMIN_TEMPLATE)),
                    ADMIN_TEMPLATE,
                    user.post(resourceRef.formatted("nothing://{id}")),
                    "nothing://{id}");
            assertThat(admin.post(promptRef.formatted(ADMIN_PROMPT)).body()).contains("root");
            assertThat(admin.post(resourceRef.formatted(ADMIN_TEMPLATE)).body()).contains("42");
        }
    }

    /**
     * Subscriptions § Acknowledgment: the acknowledgment lists what the server agreed to honor. A hidden
     * URI is acknowledged like an unknown one, and never followed.
     */
    @Test
    void aListenerIsAcknowledgedAHiddenResourceLikeAnUnknownOneAndNeverHearsAboutIt() throws Exception {
        try (var user = as(USER);
                var stream = user.openPostStream(null, """
                        {"jsonrpc":"2.0","id":4,"method":"subscriptions/listen",
                         "params":{"notifications":{"resourceSubscriptions":["%s","%s","notes://nothing"]}}}
                        """.formatted(ADMIN_NOTES, PUBLIC_NOTES))) {
            final var ack = stream.await(frame -> frame.data().contains("acknowledged"), ofSeconds(5));
            assertThat(ack.data()).contains(ADMIN_NOTES, PUBLIC_NOTES, "notes://nothing");

            server.resources().notifyResourceUpdated(ADMIN_NOTES);
            server.resources().notifyResourceUpdated(PUBLIC_NOTES);

            stream.await(frame -> isUpdateOf(frame, PUBLIC_NOTES), ofSeconds(5));
            stream.assertNoneArrived(frame -> isUpdateOf(frame, ADMIN_NOTES), ofMillis(200));
        }
    }

    private static boolean isUpdateOf(SseFrame frame, String uri) {
        return frame.data().contains("notifications/resources/updated")
                && frame.data().contains(uri);
    }

    /**
     * Scopes do not hide: § Scope Selection Strategy has scopes_supported the minimal set and more scopes
     * requested by step-up, which needs the tool to stay visible. A caller without the scope sees the
     * tool and is answered {@code 403}; one whose token implies it, after the verifier expanded the
     * hierarchy (2026-07-28 § Step-Up: servers MUST account for scope hierarchies), is served.
     */
    @Test
    void aToolWhoseScopeTheCallerLacksIsListedAndAnsweredForbiddenNotHidden() throws Exception {
        try (var user = as(USER);
                var writer = as(WRITER)) {
            assertThat(user.post(list("tools/list")).body()).contains(READ_FILES);

            final var refused = user.post(call(READ_FILES));
            final var served = writer.post(call(READ_FILES));

            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(refused.headers().allValues("WWW-Authenticate"))
                    .containsExactly("Bearer error=\"insufficient_scope\", scope=\"files:read\", resource_metadata=\""
                            + TestBearerTokens.METADATA_URL + "\"");
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).contains("read");
            assertThat(guarded.ran()).containsExactly(READ_FILES);
        }
    }

    /** A client allowed by its Client ID Metadata Document URL sees the tool; another client does not. */
    @Test
    void aToolCanBeLimitedToTheClientsTheServerTrusts() throws Exception {
        try (var partner = as(PARTNER);
                var stranger = as(STRANGER)) {
            assertThat(partner.post(list("tools/list")).body()).contains(PARTNER_TOOL);
            assertThat(stranger.post(list("tools/list")).body()).doesNotContain(PARTNER_TOOL);

            assertThat(partner.post(call(PARTNER_TOOL)).body()).contains("ran " + PARTNER_TOOL);
            assertAnswersLikeAnUnknown(
                    stranger.post(call(PARTNER_TOOL)),
                    PARTNER_TOOL,
                    stranger.post(call("no-such-tool")),
                    "no-such-tool");
            assertThat(guarded.ran()).containsExactly(PARTNER_TOOL);
        }
    }

    @Test
    void anAuthenticationExceptionWhileServingIsUnauthorizedWithTheServersOwnChallenge() throws Exception {
        try (var user = as(USER)) {
            final var response = user.post(call(GuardedFeatures.EARLY_REFUSAL));

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().allValues("WWW-Authenticate"))
                    .as("the server's invalid_token challenge, which names the metadata, not the bare one thrown")
                    .containsExactly(TestBearerTokens.INVALID_TOKEN_CHALLENGE);
            assertThat(response.body()).isEmpty();
        }
    }

    /**
     * Once {@code 200} and the event-stream head are on the wire there is no status left to send: a
     * refusal raised after that is the request's JSON-RPC error, the last event of the stream.
     */
    @Test
    void aRefusalAfterTheStreamStartedIsAJsonRpcErrorOnTheStream() throws Exception {
        try (var user = as(USER)) {
            final var response = user.post(call(GuardedFeatures.LATE_REFUSAL));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
            assertThat(response.body()).contains("\"code\":-32600", "Unauthorized");
        }
    }

    /** A policy that fails is not "allowed": the request fails, and nothing runs. */
    @Test
    void aPolicyThatThrowsFailsClosed() throws Exception {
        guarded.sabotage(Sabotage.THROWS);
        try (var admin = as(ADMIN)) {
            final var called = admin.post(call(PUBLIC_TOOL));
            final var listed = admin.post(list("tools/list"));

            assertThat(called.body()).contains("\"code\":-32603").doesNotContain("ran ");
            assertThat(listed.body()).contains("\"code\":-32603").doesNotContain(PUBLIC_TOOL);
            assertThat(guarded.ran()).isEmpty();
        }
    }

    @Test
    void aPolicyMayRefuseTheCredentialsWithAnUnauthorized() throws Exception {
        guarded.sabotage(Sabotage.REJECTS);
        try (var admin = as(ADMIN)) {
            final var response = admin.post(call(PUBLIC_TOOL));

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate"))
                    .hasValueSatisfying(
                            challenge -> assertThat(challenge).startsWith("Bearer error=\"invalid_token\""));
            assertThat(guarded.ran()).isEmpty();
        }
    }

    /**
     * A request refused on its head never reaches dispatch, yet observers hear of it: one {@code
     * AUTHENTICATION} operation with the status, and nothing from the request that could hold a token.
     */
    @Test
    void observersHearOfEveryRefusalOnTheHeadAndInDispatch() throws Exception {
        try (var nobody = new Mcp20260728Client(port);
                var user = as(USER)) {
            assertThat(nobody.post(list("tools/list")).statusCode()).isEqualTo(401);
            assertThat(user.post(call(READ_FILES)).statusCode()).isEqualTo(403);
        }

        assertThat(guarded.observed())
                .anySatisfy(recorded -> {
                    assertThat(recorded.info().kind()).isEqualTo(OperationKind.AUTHENTICATION);
                    assertThat(recorded.info().method()).isEqualTo(OperationInfo.AUTHENTICATION_METHOD);
                    assertThat(recorded.info().traceparent()).isNull();
                    assertThat(recorded.info().requestPayload()).isNull();
                    assertThat(recorded.outcome()).isEqualTo(new OperationOutcome.Rejected(null, 401, 0));
                })
                .anySatisfy(recorded -> {
                    assertThat(recorded.info().method()).isEqualTo("tools/call");
                    assertThat(recorded.outcome()).isEqualTo(new OperationOutcome.Rejected(null, 403, 0));
                });
    }
}
