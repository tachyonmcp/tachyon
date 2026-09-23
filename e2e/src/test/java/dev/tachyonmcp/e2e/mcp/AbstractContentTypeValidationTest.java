/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.McpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the POST {@code Content-Type} rule: a JSON-RPC body must be sent as {@code application/json},
 * or the server answers {@code 415}. Browsers send {@code text/plain}, form and multipart bodies as
 * CORS "simple" requests without a preflight, so accepting them would let any admitted origin drive
 * tools without CORS ever being consulted. Only the request shape differs between protocol
 * revisions, supplied by subclasses in {@code v2025_11_25}/{@code v2026_07_28}.
 *
 * <p>Each call carries a unique tag the {@code record} tool remembers, proving whether a request
 * reached dispatch without depending on test ordering. Requests always go to {@code /mcp}; a
 * subclass may configure the endpoint differently (e.g. {@code /mcp/}) to prove the rule follows the
 * endpoint the server actually serves.
 */
public abstract class AbstractContentTypeValidationTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    private static final String TOOL = "record";

    /** Before the body is read there is no request id to echo, hence {@code "id": null}. */
    // language=JSON
    private static final String UNSUPPORTED_MEDIA_TYPE_ERROR = """
            {
              "jsonrpc": "2.0",
              "id": null,
              "error": {
                "code": -32600,
                "message": "Unsupported Media Type: Content-Type must be application/json"
              }
            }
            """;

    private final Set<String> recordedTags = ConcurrentHashMap.newKeySet();

    /** Returns a {@code tools/call} of the {@code record} tool with {@code {"tag": tag}}. */
    protected abstract String toolCallBody(String tag);

    /** Returns the protocol headers {@link #toolCallBody(String)} needs. */
    protected abstract Map<String, String> requestHeaders();

    /** Returns the endpoint path the server is configured with; requests always target {@code /mcp}. */
    protected String configuredEndpointPath() {
        return "/mcp";
    }

    @Override
    protected void startDefaultServer() {
        startServer(
                b -> b.capabilities(c -> c.tools()).network(n -> n.endpointPath(configuredEndpointPath())),
                s -> s.tools()
                        .register(
                                d -> d.name(TOOL).description("Records the tag it is called with"), (ctx, request) -> {
                                    recordedTags.add(request.arguments().stringOr("tag", ""));
                                    return ToolResult.text("recorded");
                                }));
    }

    private HttpResponse<String> post(String tag, @Nullable String contentType, @Nullable String origin)
            throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toolCallBody(tag)));
        requestHeaders().forEach(builder::header);
        if (contentType != null) builder.header("Content-Type", contentType);
        if (origin != null) builder.header("Origin", origin);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String newTag() {
        return UUID.randomUUID().toString();
    }

    private void assertRejectedBeforeDispatch(HttpResponse<String> response, String tag) {
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.headers().firstValue("content-type")).hasValue("application/json");
        assertThatJson(response.body()).isEqualTo(UNSUPPORTED_MEDIA_TYPE_ERROR);
        assertThat(response.headers().firstValue("connection"))
                .hasValueSatisfying(v -> assertThat(v).isEqualToIgnoringCase("close"));
        assertThat(recordedTags).as("the tool must not have run").doesNotContain(tag);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "text/plain;charset=UTF-8",
                "text/plain; charset=application/json",
                "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x",
                "application/jsonx",
                "application/json-seq"
            })
    void rejectsNonJsonContentType(String contentType) throws Exception {
        var tag = newTag();

        assertRejectedBeforeDispatch(post(tag, contentType, null), tag);
    }

    @Test
    void rejectsMissingContentType() throws Exception {
        var tag = newTag();

        assertRejectedBeforeDispatch(post(tag, null, null), tag);
    }

    @Test
    void browserSimpleRequestFromAdmittedOriginNeverReachesTool() throws Exception {
        var tag = newTag();

        var response = post(tag, "text/plain;charset=UTF-8", "http://localhost:5173");

        assertRejectedBeforeDispatch(response, tag);
        assertThat(response.headers().firstValue("access-control-allow-origin"))
                .as("the admitted page can read why it was refused")
                .isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json; charset=utf-8", "Application/JSON"})
    void acceptsJsonContentType(String contentType) throws Exception {
        var tag = newTag();

        var response = post(tag, contentType, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("recorded");
        assertThat(recordedTags).contains(tag);
    }
}
