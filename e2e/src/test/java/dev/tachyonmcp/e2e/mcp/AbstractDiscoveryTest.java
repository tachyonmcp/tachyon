/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.BearerConfig;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * OAuth discovery for a bearer-token server. MCP authorization (2025-11-25, 2026-07-28) §
 * Authorization Server Discovery and § Protected Resource Metadata Discovery Requirements: the server
 * MUST implement RFC 9728, name at least one authorization server, and SHOULD put the metadata URL and
 * the scopes in its {@code 401} challenge. RFC 9728 §3 and §3.1 fix where the metadata is served and §5.1
 * the {@code resource_metadata} parameter. Only the request shape differs between protocol revisions,
 * supplied by subclasses in {@code v2025_11_25}/{@code v2026_07_28}.
 *
 * <p>The server is configured with a public resource URI that is not the address under test, so
 * everything a client reads must come from configuration, never from the request.
 */
public abstract class AbstractDiscoveryTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource/mcp";
    private static final String ROOT_PATH = "/.well-known/oauth-protected-resource";
    private static final String BROWSER_ORIGIN = "http://localhost:5173";

    // language=json
    private static final String METADATA = """
            {
              "resource": "https://mcp.example.test/mcp",
              "authorization_servers": ["https://auth.example.test"],
              "scopes_supported": ["tools:read", "tools:call"],
              "bearer_methods_supported": ["header"]
            }
            """;

    private TestBearerTokens tokens = new TestBearerTokens();

    /** Returns a JSON-RPC request this protocol revision accepts, which the server refuses unauthenticated. */
    protected abstract String requestBody();

    /** Returns the protocol headers {@link #requestBody()} needs. */
    protected abstract Map<String, String> requestHeaders();

    @BeforeEach
    void startSecuredServer() {
        tokens = new TestBearerTokens();
        startServer(
                b -> b.capabilities(c -> c.tools()).security(TestBearerTokens.secured(tokens)), tokens::registerWhoAmI);
    }

    @Test
    void aRequestWithoutATokenIsChallengedWithTheMetadataUrlAndTheScopes() throws Exception {
        final var response = post(Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(TestBearerTokens.CHALLENGE);
    }

    @Test
    void aBadTokenKeepsItsErrorAndGainsTheSameDiscoveryParameters() throws Exception {
        final var response = post(Map.of("Authorization", "Bearer unknown-token"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("WWW-Authenticate"))
                .containsExactly(TestBearerTokens.INVALID_TOKEN_CHALLENGE);
    }

    @Test
    void theMetadataUrlOfTheChallengeIsServedByTheServerThatNamedIt() throws Exception {
        final var challenge =
                post(Map.of()).headers().firstValue("WWW-Authenticate").orElseThrow();
        final var announced = URI.create(challenge.replaceAll(".*resource_metadata=\"([^\"]+)\".*", "$1"));

        final var response = get(announced.getRawPath(), Map.of());

        assertThat(announced).hasToString(TestBearerTokens.METADATA_URL);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThatJson(response.body()).isEqualTo(METADATA);
    }

    @ParameterizedTest
    @ValueSource(strings = {METADATA_PATH, ROOT_PATH})
    void servesTheMetadataToAClientWithoutAToken(String path) throws Exception {
        final var response = get(path, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.headers().firstValue("Content-Length"))
                .hasValue(String.valueOf(response.body().getBytes(StandardCharsets.UTF_8).length));
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThatJson(response.body()).isEqualTo(METADATA);
        assertThat(response.body())
                .as("one document, rendered once, in the order RFC 9728 §2 lists it")
                .isEqualTo("""
                        {"resource":"https://mcp.example.test/mcp","authorization_servers":["https://auth.example.test"],"scopes_supported":["tools:read","tools:call"],"bearer_methods_supported":["header"]}""");
        assertThat(tokens.verifierThreads()).as("no token, no verification").isEmpty();
    }

    @Test
    void aBrowserPageOnALoopbackOriginCanReadTheMetadata() throws Exception {
        final var response = get(METADATA_PATH, Map.of("Origin", BROWSER_ORIGIN));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
                .isEmpty();
        assertThatJson(response.body()).isEqualTo(METADATA);
    }

    @Test
    void aBrowserPageCanReadTheChallengeOfACrossOriginRefusal() throws Exception {
        final var response = post(Map.of("Origin", BROWSER_ORIGIN));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(exposedHeaders(response))
                .as("fetch() hides headers that are not exposed, and this is the one that starts OAuth")
                .contains("www-authenticate", "mcp-session-id", "mcp-protocol-version");
    }

    @Test
    void aSpoofedHostOrForwardedHeaderChangesNeitherTheMetadataNorTheChallenge() throws Exception {
        final var spoofed = "Host: localhost:" + port + "\r\n"
                + "X-Forwarded-Host: evil.example.com\r\n"
                + "X-Forwarded-Proto: http\r\n"
                + "X-Forwarded-Port: 1\r\n"
                + "Forwarded: host=evil.example.com;proto=http\r\n";

        final var responses = exchange(
                "GET " + METADATA_PATH + " HTTP/1.1\r\n" + spoofed + "\r\n",
                "POST /mcp HTTP/1.1\r\n" + spoofed + "Content-Type: application/json\r\n"
                        + "Accept: application/json, text/event-stream\r\n"
                        + requestHeaderLines()
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n");

        assertThat(responses.get(0).status()).isEqualTo(200);
        assertThatJson(responses.get(0).body()).isEqualTo(METADATA);
        assertThat(responses.get(1).status()).isEqualTo(401);
        assertThat(responses.get(1).header("www-authenticate")).isEqualTo(TestBearerTokens.CHALLENGE);
    }

    @Test
    void anAbsoluteFormTargetIsServedLikeAnyOther() throws Exception {
        final var responses = exchange("GET http://localhost:" + port + METADATA_PATH + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\nConnection: close\r\n\r\n");

        assertThat(responses.getFirst().status()).isEqualTo(200);
        assertThatJson(responses.getFirst().body()).isEqualTo(METADATA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example.com:%d", "0.0.0.0:%d", "localhost.attacker.example.com:%d"})
    void aReboundHostIsRefusedLikeOnTheMcpEndpoint(String hostTemplate) throws Exception {
        final var responses = exchange("GET " + METADATA_PATH + " HTTP/1.1\r\nHost: " + hostTemplate.formatted(port)
                + "\r\nConnection: close\r\n\r\n");

        assertThat(responses.getFirst().status()).isEqualTo(403);
        assertThat(responses.getFirst().body()).doesNotContain("authorization_servers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example.com", "null", "http://localhost.evil.example.com"})
    void aForeignOriginIsRefusedAndReadsNothing(String origin) throws Exception {
        final var response = get(METADATA_PATH, Map.of("Origin", origin));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("authorization_servers");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    /** RFC 9110 §9.1: a general-purpose server supports {@code HEAD}; §9.3.2: the headers of the {@code GET}, no body. */
    @ParameterizedTest
    @ValueSource(strings = {METADATA_PATH, ROOT_PATH})
    void headAnswersWithTheHeadersOfGetAndNoBody(String path) throws Exception {
        final var get = get(path, Map.of());

        final var head = send("HEAD", path, Map.of("Origin", BROWSER_ORIGIN));

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(head.headers().firstValue("Content-Length"))
                .hasValue(String.valueOf(get.body().getBytes(StandardCharsets.UTF_8).length));
        assertThat(head.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(head.body()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void onlyGetAndHeadAreAllowed(String method) throws Exception {
        final var response = send(method, METADATA_PATH, Map.of());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().allValues("Allow")).containsExactly("GET, HEAD");
        assertThat(response.body()).isEmpty();
    }

    /**
     * A page that sends {@code MCP-Protocol-Version} on the metadata request triggers a preflight, and
     * if that fails the client never learns the authorization server. The server's one CORS policy
     * answers it, as it does for the MCP endpoint.
     */
    @ParameterizedTest
    @ValueSource(strings = {METADATA_PATH, ROOT_PATH})
    void aBrowserPreflightToTheMetadataSucceedsAndSoDoesTheRequestItGates(String path) throws Exception {
        final var version = requestHeaders().get("MCP-Protocol-Version");

        final var preflight = send(
                "OPTIONS",
                path,
                Map.of(
                        "Origin",
                        BROWSER_ORIGIN,
                        "Access-Control-Request-Method",
                        "GET",
                        "Access-Control-Request-Headers",
                        "mcp-protocol-version"));
        final var metadata = get(path, Map.of("Origin", BROWSER_ORIGIN, "MCP-Protocol-Version", version));

        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
                .hasValue("*");
        assertThat(headerTokens(preflight, "Access-Control-Allow-Methods")).contains("get");
        assertThat(headerTokens(preflight, "Access-Control-Allow-Headers")).contains("mcp-protocol-version");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials"))
                .isEmpty();
        assertThat(preflight.body()).isEmpty();
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(metadata.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThatJson(metadata.body()).isEqualTo(METADATA);
    }

    @Test
    void aPreflightFromAForeignOriginIsRefusedLikeOnTheMcpEndpoint() throws Exception {
        final var preflight = send(
                "OPTIONS",
                METADATA_PATH,
                Map.of(
                        "Origin",
                        "http://evil.example.com",
                        "Access-Control-Request-Method",
                        "GET",
                        "Access-Control-Request-Headers",
                        "mcp-protocol-version"));

        assertThat(preflight.statusCode()).isEqualTo(403);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
                .isEmpty();
    }

    @Test
    void aConnectionServesTheMetadataAndThenTheMcpEndpoint() throws Exception {
        final var metadata = "GET " + METADATA_PATH + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n";

        final var responses = exchange(
                metadata,
                metadata,
                "GET " + ROOT_PATH + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n",
                "POST /mcp HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Type: application/json\r\n"
                        + "Accept: application/json, text/event-stream\r\n" + requestHeaderLines()
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n");

        assertThat(responses.stream().map(CorsRawResponse::status)).containsExactly(200, 200, 200, 401);
        assertThatJson(responses.get(0).body()).isEqualTo(METADATA);
        assertThatJson(responses.get(2).body()).isEqualTo(METADATA);
        assertThat(responses.get(3).header("www-authenticate")).isEqualTo(TestBearerTokens.CHALLENGE);
    }

    @Test
    void aGetThatCarriesABodyDoesNotDisturbTheNextRequest() throws Exception {
        final var responses = exchange(
                "GET " + METADATA_PATH + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Length: 5\r\n\r\nhello",
                "GET " + METADATA_PATH + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\n\r\n");

        assertThat(responses.stream().map(CorsRawResponse::status)).containsExactly(200, 200);
        assertThatJson(responses.get(1).body()).isEqualTo(METADATA);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/.well-known/oauth-protected-resource/other",
                "/.well-known/oauth-protected-resource/mcp/extra",
                "/.well-known/oauth-authorization-server",
                "/.well-known/openid-configuration"
            })
    void everyOtherWellKnownPathIsNotFound(String path) throws Exception {
        final var response = get(path, Map.of("Origin", BROWSER_ORIGIN));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void theMetadataIsServedAtThePathOfTheResourceNotOfTheEndpoint() throws Exception {
        startServer(
                b -> b.capabilities(c -> c.tools())
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .bearerToken(
                                        bearer -> bearer.resource(URI.create("https://mcp.example.test/tenant/mcp"))
                                                .authorizationServers(TestBearerTokens.AUTHORIZATION_SERVER)
                                                .verifier(tokens))),
                tokens::registerWhoAmI);

        final var inserted = get("/.well-known/oauth-protected-resource/tenant/mcp", Map.of());

        assertThat(inserted.statusCode()).isEqualTo(200);
        // language=json
        assertThatJson(inserted.body()).isEqualTo("""
                {
                  "resource": "https://mcp.example.test/tenant/mcp",
                  "authorization_servers": ["https://auth.example.test"],
                  "bearer_methods_supported": ["header"]
                }
                """);
        assertThat(get(ROOT_PATH, Map.of()).statusCode()).isEqualTo(200);
        assertThat(get(METADATA_PATH, Map.of()).statusCode())
                .as("the endpoint's own path is not the resource's")
                .isEqualTo(404);
        assertThat(post(Map.of()).headers().allValues("WWW-Authenticate"))
                .containsExactly("Bearer resource_metadata=\""
                        + "https://mcp.example.test/.well-known/oauth-protected-resource/tenant/mcp\"");
    }

    @Test
    void aServerWithoutBearerTokensPublishesNothingAndChallengesNoOne() throws Exception {
        startServer(b -> b.capabilities(c -> c.tools()));

        assertThat(get(METADATA_PATH, Map.of()).statusCode()).isEqualTo(404);
        assertThat(get(ROOT_PATH, Map.of()).statusCode()).isEqualTo(404);
        assertThat(post(Map.of()).headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void aBearerServerThatCannotWorkIsRefusedWhenConfigured() {
        final var authorizationServer = TestBearerTokens.AUTHORIZATION_SERVER;
        final var resource = TestBearerTokens.RESOURCE;
        final List<Consumer<BearerConfig.Builder>> refused = List.of(
                b -> b.resource(resource).verifier(tokens),
                b -> b.resource(resource).authorizationServers().verifier(tokens),
                b -> b.resource(resource)
                        .authorizationServers(URI.create("http://auth.example.test"))
                        .verifier(tokens),
                b -> b.resource(resource)
                        .authorizationServers(authorizationServer)
                        .scopesSupported("tools:read", "offline_access")
                        .verifier(tokens),
                b -> b.resource(URI.create("https://mcp.example.test/mcp?tenant=1"))
                        .authorizationServers(authorizationServer)
                        .verifier(tokens),
                b -> b.authorizationServers(authorizationServer).verifier(tokens));

        for (final var configurer : refused) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TachyonServer.builder()
                            .security(s ->
                                    s.accessPolicy(AccessPolicy.permitAll()).bearerToken(configurer)));
        }
    }

    private HttpResponse<String> post(Map<String, String> headers) throws Exception {
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody()));
        requestHeaders().forEach(builder::header);
        headers.forEach(builder::header);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        return send("GET", path, headers);
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> headers) throws Exception {
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String requestHeaderLines() {
        final var lines = new StringBuilder();
        requestHeaders()
                .forEach((name, value) ->
                        lines.append(name).append(": ").append(value).append("\r\n"));
        return lines.toString();
    }

    private static List<String> exposedHeaders(HttpResponse<String> response) {
        return headerTokens(response, "Access-Control-Expose-Headers");
    }

    private static List<String> headerTokens(HttpResponse<String> response, String name) {
        return response.headers().allValues(name).stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(token -> token.strip().toLowerCase(Locale.ROOT))
                .toList();
    }

    /** Sends the requests one after another on one connection and reads one response to each. */
    private List<CorsRawResponse> exchange(String... requests) throws IOException {
        final var responses = new ArrayList<CorsRawResponse>();
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            for (final var request : requests) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                responses.add(CorsRawResponse.read(socket.getInputStream()));
            }
        }
        return responses;
    }
}
