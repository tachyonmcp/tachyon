/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.METADATA_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.testkit.McpClient;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A tool that declares the scopes it requires is refused, before it runs, for a caller who lacks one.
 * MCP authorization (2025-11-25, 2026-07-28) § Runtime Insufficient Scope: insufficient scope is HTTP
 * {@code 403} with {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="…",
 * resource_metadata="…"}, so a client can step up; 2026-07-28 § Server Scope Management: SHOULD name
 * every required scope in one challenge. RFC 6750 §3.1: {@code insufficient_scope} is {@code 403}.
 * Only the request shape differs between protocol revisions, supplied by subclasses in {@code
 * v2025_11_25}/{@code v2026_07_28}.
 */
public abstract class AbstractRequiredScopesTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    private static final String BROWSER_ORIGIN = "http://localhost:5173";

    private static final String WRITE_CHALLENGE =
            "Bearer error=\"insufficient_scope\", scope=\"tools:call\", resource_metadata=\"" + METADATA_URL + "\"";
    private static final String AUDIT_CHALLENGE = "Bearer error=\"insufficient_scope\", "
            + "scope=\"tools:call admin:audit\", resource_metadata=\"" + METADATA_URL + "\"";

    private TestBearerTokens tokens = new TestBearerTokens();
    private ScopedTools scoped = new ScopedTools();

    /** Returns a {@code tools/call} of {@code tool} with no arguments, as this protocol revision words it. */
    protected abstract String toolCallBody(String tool);

    /** Returns the protocol headers a {@code tools/call} of {@code tool} carries. */
    protected abstract Map<String, String> toolCallHeaders(String tool);

    /** Returns a {@code tools/list} request. */
    protected abstract String listBody();

    /** Returns the protocol headers a {@code tools/list} carries. */
    protected abstract Map<String, String> listHeaders();

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        scoped = new ScopedTools();
        startServer(b -> b.capabilities(c -> c.tools()).security(TestBearerTokens.secured(tokens)), server -> {
            tokens.registerWhoAmI(server);
            scoped.register(server);
        });
    }

    @Test
    void aCallerWithoutTheScopeIsForbiddenWithTheChallengeAndTheToolNeverRuns() throws Exception {
        final var response = call(ScopedTools.WRITE, BOB, Map.of());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(WRITE_CHALLENGE);
        assertThat(response.body())
                .as("transport-level, never a JSON-RPC error")
                .isEmpty();
        assertThat(response.headers().firstValue("Content-Length")).hasValue("0");
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        assertThat(scoped.ran()).isEmpty();
    }

    @Test
    void aCallerWithTheScopeIsServed() throws Exception {
        final var response = call(ScopedTools.WRITE, ALICE, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("written");
        assertThat(scoped.ran()).containsExactly(ScopedTools.WRITE);
    }

    @Test
    void theChallengeNamesEveryRequiredScopeNotOnlyTheMissingOne() throws Exception {
        final var alice = call(ScopedTools.AUDIT, ALICE, Map.of());
        final var bob = call(ScopedTools.AUDIT, BOB, Map.of());

        for (final var response : List.of(alice, bob)) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(AUDIT_CHALLENGE);
        }
        assertThat(scoped.ran()).isEmpty();
    }

    @Test
    void aToolThatDeclaresNoScopesIsUnaffected() throws Exception {
        final var response = call(TestBearerTokens.WHOAMI, BOB, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(tokens.toolCallers()).containsExactly("bob [tools:read]");
    }

    @Test
    void theScopesAreNeitherListedNorUsedToHideATool() throws Exception {
        final var response = list(BOB);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("a caller sees every tool, and no scope")
                .contains("\"" + ScopedTools.WRITE + "\"", "\"" + ScopedTools.AUDIT + "\"", "\"whoami\"")
                .doesNotContain("admin:audit", "tools:call", "requiredScopes", "insufficient");
    }

    /**
     * The decision comes from the tool the body names. A mirror header that disagrees with the body is
     * refused by the header check before any policy runs (HTTP {@code 400} in 2026-07-28, a JSON-RPC
     * error in 2025-11-25), in either direction: it can neither smuggle a scoped call under an unscoped
     * name nor make an unscoped call look scoped. Nothing is served and nothing runs.
     */
    @Test
    void aForgedMirrorHeaderChangesNothing() throws Exception {
        final var scopedBodyUnscopedHeader =
                call(ScopedTools.WRITE, BOB, Map.of("Mcp-Method", "tools/call", "Mcp-Name", TestBearerTokens.WHOAMI));
        final var unscopedBodyScopedHeader =
                call(TestBearerTokens.WHOAMI, BOB, Map.of("Mcp-Method", "tools/call", "Mcp-Name", ScopedTools.WRITE));

        for (final var response : List.of(scopedBodyUnscopedHeader, unscopedBodyScopedHeader)) {
            assertThat(response.statusCode() == 400 || response.body().contains("\"error\""))
                    .as("refused as a mismatch, not served: %s %s", response.statusCode(), response.body())
                    .isTrue();
            assertThat(response.body()).doesNotContain("\"result\"");
        }
        assertThat(scoped.ran()).isEmpty();
        assertThat(tokens.toolCallers()).isEmpty();
    }

    @Test
    void aStreamedCallIsRefusedBeforeAnyEventStreamByte() throws Exception {
        final var served = call(ScopedTools.STREAM, ALICE, Map.of());
        final var refused = call(ScopedTools.STREAM, BOB, Map.of());

        assertThat(served.headers().firstValue("Content-Type"))
                .as("the tool does stream for a caller who may")
                .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(served.body()).contains("streamed");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().firstValue("Content-Type")).isEmpty();
        assertThat(refused.body()).isEmpty();
        assertThat(scoped.ran()).containsExactly(ScopedTools.STREAM);
    }

    @Test
    void aBrowserPageCanReadTheChallengeItMustStepUpFrom() throws Exception {
        final var response = call(ScopedTools.WRITE, BOB, Map.of("Origin", BROWSER_ORIGIN));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(response.headers().allValues("Access-Control-Expose-Headers").stream()
                        .flatMap(value -> Arrays.stream(value.split(",")))
                        .map(name -> name.strip().toLowerCase(Locale.ROOT)))
                .contains("www-authenticate");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(WRITE_CHALLENGE);
    }

    @Test
    void theConnectionServesTheNextRequestAfterARefusal() throws Exception {
        final var responses = exchange(
                rawCall(ScopedTools.WRITE, BOB),
                rawCall(TestBearerTokens.WHOAMI, BOB),
                rawCall(ScopedTools.WRITE, ALICE));

        assertThat(responses.stream().map(CorsRawResponse::status)).containsExactly(403, 200, 200);
        assertThat(responses.get(0).header("www-authenticate")).isEqualTo(WRITE_CHALLENGE);
        assertThat(responses.get(2).body()).contains("written");
        assertThat(scoped.ran()).containsExactly(ScopedTools.WRITE);
    }

    /** {@code insufficient_scope} is an RFC 6750 Bearer error: another scheme gets the status and no challenge. */
    @Test
    void aCustomProviderGetsTheStatusAndNoChallenge() throws Exception {
        startServer(
                b -> b.capabilities(c -> c.tools())
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .authenticationProvider(request -> SecurityContext.authenticated(
                                        () -> "carol",
                                        request.headers("X-Scopes").isEmpty()
                                                ? Set.of("tools:read")
                                                : Set.of("tools:call")))),
                scoped::register);

        final var refused = call(ScopedTools.WRITE, null, Map.of());
        final var served = call(ScopedTools.WRITE, null, Map.of("X-Scopes", "yes"));

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(refused.body()).isEmpty();
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(scoped.ran()).containsExactly(ScopedTools.WRITE);
    }

    /** With no authentication every caller is anonymous, so a declared scope could never be held. */
    @Test
    void aToolThatRequiresScopesCannotBeRegisteredOnAServerThatDoesNotAuthenticate() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TachyonServer.builder()
                        .withTools(tools -> tools.register(
                                d -> d.name(ScopedTools.WRITE).requiredScopes("tools:call"),
                                (ctx, request) -> ToolResult.empty()))
                        .build())
                .withMessageContaining("Tool 'write' declares required scopes [tools:call]");
    }

    private HttpResponse<String> call(String tool, @Nullable String token, Map<String, String> extra) throws Exception {
        return post(toolCallBody(tool), toolCallHeaders(tool), token, extra);
    }

    private HttpResponse<String> list(@Nullable String token) throws Exception {
        return post(listBody(), listHeaders(), token, Map.of());
    }

    private HttpResponse<String> post(
            String body, Map<String, String> protocol, @Nullable String token, Map<String, String> extra)
            throws Exception {
        final var headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.putAll(protocol);
        headers.putAll(extra);
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String rawCall(String tool, String token) {
        final var body = toolCallBody(tool);
        final var lines = new StringBuilder();
        toolCallHeaders(tool)
                .forEach((name, value) ->
                        lines.append(name).append(": ").append(value).append("\r\n"));
        return "POST /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "Authorization: Bearer " + token + "\r\n" + lines
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    /** Sends the requests one after another on one connection and reads one response to each. */
    private List<CorsRawResponse> exchange(String... requests) throws IOException {
        final var responses = new ArrayList<CorsRawResponse>();
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            for (final var request : requests) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                responses.add(CorsRawResponse.read(socket.getInputStream()));
            }
        }
        return responses;
    }
}
