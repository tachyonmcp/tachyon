/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.net.ssl.SSLSession;
import me.kpavlov.finchly.queue.MessageAggregator;
import org.intellij.lang.annotations.Language;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Base HTTP client for driving a Tachyon MCP server over Streamable HTTP.
 *
 * <p>A subclass fixes the protocol version via {@link #protocolVersion()} and may override {@link
 * #requestBody(String)} / {@link #configureRequest(HttpRequest.Builder, String)} to shape requests
 * for that revision. Instantiate via {@link McpTestClients} or directly with the server's bound
 * port. Use {@code port()} on a {@code port(0)}-started MCP Server.
 */
public abstract class McpClient implements Closeable {

    private static final Duration DEFAULT_TASK_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration DEFAULT_NOTIFICATION_TIMEOUT = Duration.ofSeconds(5);
    /** Shared Jackson ObjectMapper for JSON-RPC serialization. */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI mcpEndpoint;
    private final HttpClient httpClient;
    private final MessageAggregator<JsonNode> notifications = new MessageAggregator<>();
    private final List<SseStream> openStreams = new CopyOnWriteArrayList<>();
    private volatile @Nullable String sessionId;
    private volatile boolean closed;

    /** Creates a client against a local, port-0-style Tachyon server ({@code http://localhost:<port>/mcp}).
     *
     * @param port the server's bound port
     */
    protected McpClient(int port) {
        this(localEndpoint(port));
    }

    /** Creates a client against an arbitrary MCP endpoint (local or remote, http or https).
     *
     * @param mcpEndpoint the MCP endpoint URI
     */
    protected McpClient(URI mcpEndpoint) {
        this.mcpEndpoint = mcpEndpoint;
        this.httpClient = HttpClient.newHttpClient();
    }

    static URI localEndpoint(int port) {
        return URI.create("http://localhost:" + port + "/mcp");
    }

    /** The MCP protocol version this client speaks; sent as {@code MCP-Protocol-Version}.
     *
     * @return the protocol version string
     */
    protected abstract String protocolVersion();

    @Override
    public void close() {
        closed = true;
        RuntimeException failure = null;
        for (var stream : openStreams) {
            try {
                stream.close();
            } catch (RuntimeException e) {
                failure = addSuppressed(failure, e);
            }
        }
        try {
            httpClient.close();
        } catch (RuntimeException e) {
            failure = addSuppressed(failure, e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException addSuppressed(@Nullable RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    /**
     * Opens a raw GET stream against this client's session, for reconnect / {@code Last-Event-ID}
     * testing. Requires {@link #initialize()} (or {@link #sendInitialized}) to have set a session
     * id first. Closed automatically when this client is {@link #close() closed}, in addition to
     * whatever the caller does with it directly.
     *
     * @param lastEventId the last event id for reconnection, or {@code null}
     * @return the SSE stream
     */
    public SseStream openGetStream(@Nullable String lastEventId) {
        return openGetStream(
                Objects.requireNonNull(sessionId, "call initialize() before openGetStream()"), lastEventId);
    }

    /** {@link #openGetStream(String)} against an explicit session id rather than this client's stored one.
     *
     * @param sessionId  the session id to use
     * @param lastEventId the last event id for reconnection, or {@code null}
     * @return the SSE stream
     */
    public SseStream openGetStream(String sessionId, @Nullable String lastEventId) {
        var subscriber = new SseStream(mcpEndpoint, sessionId, lastEventId, protocolVersion());
        openStreams.add(subscriber);
        subscriber.start();
        return subscriber;
    }

    /**
     * Performs the {@code initialize}/{@code notifications/initialized} handshake and returns the
     * session id issued by the server.
     *
     * @return the session id issued by the server, or {@code null}
     * @throws Exception if the handshake fails
     * @throws UnsupportedOperationException on protocol revisions that removed {@code initialize}
     */
    public @Nullable String initialize() throws Exception {
        sessionId = null;
        // language=JSON
        var initBody = """
            {
              "jsonrpc":"2.0",
              "id":1,
              "method":"initialize",
              "params":{
                "protocolVersion":"%s",
                "capabilities":{},
                "clientInfo":{
                  "name":"test",
                  "version":"1.0"
                }
              }
            }
            """.formatted(protocolVersion());
        var response = httpClient.send(
                baseRequest()
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("initialize response: %s", response.body())
                .isEqualTo(200);
        var responseJson = MAPPER.readTree(response.body());
        assertThat(responseJson.path("result").path("protocolVersion").asString())
                .as("negotiated protocol version in: %s", response.body())
                .isEqualTo("2025-11-25");
        var sessionId = response.headers().firstValue("MCP-Session-Id").orElse(null);
        sendInitialized(sessionId);
        return sessionId;
    }

    /**
     * Sends the {@code notifications/initialized} notification for the given session.
     *
     * @param sessionId the session id returned by {@link #initialize()}, or {@code null}
     * @throws Exception if the notification fails
     */
    public void sendInitialized(@Nullable String sessionId) throws Exception {
        var response = post(sessionId, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        assertThat(response.statusCode())
                .as("notifications/initialized response: %s", response.body())
                .isEqualTo(202);
        assertThat(response.body())
                .as("notifications/initialized response body")
                .isEmpty();
        this.sessionId = sessionId;
    }

    /** POSTs an MCP request without a session.
     *
     * @param body the JSON-RPC request body
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> post(@Language("json") String body) throws Exception {
        return post(null, body);
    }

    /**
     * POSTs an MCP request carrying extra HTTP headers (e.g. {@code Mcp-Param-*} custom headers).
     *
     * @param body         the JSON-RPC request body
     * @param extraHeaders additional HTTP headers to include
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> post(@Language("json") String body, Map<String, String> extraHeaders) throws Exception {
        return post(null, body, extraHeaders);
    }

    /** POSTs an MCP request for the given session.
     *
     * @param sessionId the session id, or {@code null}
     * @param body      the JSON-RPC request body
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> post(@Nullable String sessionId, String body) throws Exception {
        return post(sessionId, body, Map.of());
    }

    /** POSTs an MCP request for the given session with extra HTTP headers.
     *
     * @param sessionId    the session id, or {@code null}
     * @param body         the JSON-RPC request body
     * @param extraHeaders additional HTTP headers to include
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> post(@Nullable String sessionId, String body, Map<String, String> extraHeaders)
            throws Exception {
        body = requestBody(body);
        var builder = requestBuilder(body);
        if (sessionId != null) {
            builder.header("MCP-Session-Id", sessionId);
        }
        extraHeaders.forEach(builder::header);
        var response = httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        captureNotifications(response.body());
        return response;
    }

    /**
     * Parses server-to-client notifications out of an SSE {@code data:} payload and stores them for
     * {@link #awaitNotification(String)}. A JSON-RPC envelope is a notification when it carries a
     * {@code method} and no {@code id}.
     */
    private void captureNotifications(String sseBody) {
        for (var line : sseBody.split("\n")) {
            String data = null;
            if (line.startsWith("data: ")) data = line.substring("data: ".length());
            else if (line.startsWith("data:")) data = line.substring("data:".length());
            if (data == null) continue;
            try {
                var envelope = MAPPER.readTree(data);
                if (envelope.has("method") && !envelope.has("id")) {
                    notifications.push(envelope);
                }
            } catch (Exception e) {
                assertThat(e)
                        .as("SSE data line must be a JSON envelope: %s", data)
                        .isNull();
            }
        }
    }

    /**
     * POSTs an MCP request carrying an {@code Origin} header, exercising DNS-rebinding protection.
     *
     * @param origin the Origin header value
     * @param body   the JSON-RPC request body
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> postWithOrigin(String origin, String body) throws Exception {
        body = requestBody(body);
        var response = httpClient.send(
                requestBuilder(body)
                        .header("Origin", origin)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        captureNotifications(response.body());
        return response;
    }

    /** Posts a {@code ping} request with the given JSON-RPC {@code id}.
     *
     * @param sessionId the session id, or {@code null}
     * @param id        the JSON-RPC request id
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> ping(@Nullable String sessionId, Object id) throws Exception {
        final var idString = MAPPER.writeValueAsString(id);
        return post(sessionId, "{\"jsonrpc\":\"2.0\",\"id\":%s,\"method\":\"ping\"}".formatted(idString));
    }

    /** Sends a notification (a JSON-RPC envelope with a {@code method} and no {@code id}).
     *
     * @param method the notification method name
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> notify(String method) throws Exception {
        return notify(method, null);
    }

    /** Sends a notification with the given params.
     *
     * @param method the notification method name
     * @param params the notification params, or {@code null}
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> notify(String method, @Nullable Object params) throws Exception {
        return post(sessionId, notificationEnvelope(method, params));
    }

    /** Builds the JSON-RPC notification envelope sent by {@link #notify(String, Object)}. */
    static String notificationEnvelope(String method, @Nullable Object params) {
        var paramsJson = params == null ? "" : ",\"params\":" + MAPPER.writeValueAsString(params);
        return "{\"jsonrpc\":\"2.0\",\"method\":\"%s\"%s}".formatted(method, paramsJson);
    }

    /**
     * Awaits a notification with the given {@code method} and returns it once it arrives, or fails
     * after {@code timeout}. The notification is left in the queue (use {@link #clearNotifications()}
     * to reset).
     *
     * @param method  the notification method name
     * @param timeout the maximum wait duration
     * @return the received notification
     */
    public Notification awaitNotification(String method, Duration timeout) {
        return awaitNotification(method, node -> true, timeout);
    }

    /**
     * Awaits a notification whose {@code method} matches and whose params satisfy {@code predicate},
     * or fails after {@code timeout}.
     *
     * @param method    the notification method name
     * @param predicate the predicate to match against the params
     * @param timeout   the maximum wait duration
     * @return the received notification
     */
    public Notification awaitNotification(String method, Predicate<JsonNode> predicate, Duration timeout) {
        final var message = notifications.awaitMessage(timeout, true, node -> {
            if (closed) {
                throw new IllegalStateException("McpClient closed while awaiting " + method);
            }
            return method.equals(node.path("method").asString()) && predicate.test(node.path("params"));
        });
        return Notification.from(message);
    }

    /** {@link #awaitNotification(String, Duration)} with a 5s timeout.
     *
     * @param method the notification method name
     * @return the received notification
     */
    public Notification awaitNotification(String method) {
        return awaitNotification(method, DEFAULT_NOTIFICATION_TIMEOUT);
    }

    /** A snapshot of all notifications received so far, in arrival order.
     *
     * @return the received notifications
     */
    public List<Notification> notifications() {
        return notifications.findAll(node -> true).stream()
                .map(Notification::from)
                .toList();
    }

    /** Removes all received notifications. */
    public void clearNotifications() {
        notifications.clear();
    }

    /** POSTs a request and returns the raw response line stream (for SSE responses).
     *
     * @param sessionId the session id, or {@code null}
     * @param body      the JSON-RPC request body
     * @return the streaming HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<Stream<String>> sendStreamingRequest(@Nullable String sessionId, @Language("json") String body)
            throws Exception {
        body = requestBody(body);
        var builder = requestBuilder(body);
        if (sessionId != null) builder.header("MCP-Session-Id", sessionId);
        return httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofLines());
    }

    /**
     * POSTs an MCP request and consumes its SSE response in the background.
     *
     * <p>The returned response is also closed when this client closes.
     *
     * @param sessionId the session id, or {@code null}
     * @param body the JSON-RPC request body
     * @return the parsed SSE stream
     * @throws Exception if the request fails
     */
    public SseStream openPostStream(@Nullable String sessionId, @Language("json") String body) throws Exception {
        final var response = sendStreamingRequest(sessionId, body);
        final var stream = new SseStream(response);
        try {
            assertThat(response.statusCode()).as("streaming response status").isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                    .as("streaming response content type")
                    .startsWith("text/event-stream");
        } catch (AssertionError failure) {
            stream.close();
            throw failure;
        }
        openStreams.add(stream);
        stream.start();
        return stream;
    }

    /**
     * {@link #sendRpc(String, String)} against the session set by {@link #initialize()}.
     *
     * @param jsonBody the JSON-RPC request body
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> sendRpc(@Language("json") String jsonBody) throws Exception {
        return sendRpc(sessionId, jsonBody);
    }

    /**
     * Sends a JSON-RPC request and returns the HTTP response, asserting a {@code 200} status. If the
     * response content-type is {@code text/event-stream}, {@link HttpResponse#body()} transparently
     * returns the matching JSON-RPC envelope extracted from the SSE body rather than the raw SSE text.
     *
     * @param sessionId the session id, or {@code null}
     * @param jsonBody  the JSON-RPC request body
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> sendRpc(@Nullable String sessionId, @Language("json") String jsonBody)
            throws Exception {
        var response = post(sessionId, jsonBody);
        assertThat(response.statusCode()).isIn(200, 404);
        var contentType = response.headers().firstValue("content-type").orElse("");
        if (contentType.startsWith("text/event-stream")) {
            var extracted = extractJsonRpcResponse(response.body(), extractRequestId(jsonBody));
            return new ExtractedJsonRpcResponse(response, extracted);
        }
        return response;
    }

    /**
     * Sends {@code tasks/get} for {@code taskId} and returns the JSON-RPC response body.
     *
     * @param sessionId the session id, or {@code null}
     * @param taskId    the task id to look up
     * @return the response body
     * @throws Exception if the request fails
     */
    public String getTask(@Nullable String sessionId, String taskId) throws Exception {
        var body = """
                {"jsonrpc":"2.0","id":"tasks-get","method":"tasks/get","params":{"taskId":%s}}
                """.formatted(MAPPER.writeValueAsString(taskId));
        return sendRpc(sessionId, body).body();
    }

    /** {@link HttpResponse} wrapper reporting the extracted JSON-RPC envelope as its {@link #body()}. */
    private record ExtractedJsonRpcResponse(HttpResponse<String> delegate, String body)
            implements HttpResponse<String> {

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public HttpRequest request() {
            return delegate.request();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return delegate.previousResponse();
        }

        @Override
        public HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return delegate.sslSession();
        }

        @Override
        public URI uri() {
            return delegate.uri();
        }

        @Override
        public HttpClient.Version version() {
            return delegate.version();
        }
    }

    /**
     * Polls {@code tasks/get} until the task reaches {@code status}, then returns the last response
     * body. Fails if {@code status} is not reached within {@code timeout}.
     *
     * @param sessionId the session id, or {@code null}
     * @param taskId    the task id to poll
     * @param status    the expected terminal status
     * @param timeout   the maximum wait duration
     * @return the last response body, or {@code null}
     */
    public @Nullable String awaitTaskStatus(
            @Nullable String sessionId, String taskId, String status, Duration timeout) {
        var lastResponse = new AtomicReference<@Nullable String>();
        var nextPollInterval = new AtomicReference<>(DEFAULT_TASK_POLL_INTERVAL);
        await().alias("task %s to reach status %s".formatted(taskId, status))
                .atMost(timeout)
                .pollDelay(Duration.ZERO)
                .pollInterval((pollCount, previousDuration) -> nextPollInterval.get())
                .until(() -> {
                    var json = getTask(sessionId, taskId);
                    lastResponse.set(json);
                    var snapshot = taskSnapshot(json, taskId);
                    if (snapshot.pollInterval() != null) {
                        nextPollInterval.set(snapshot.pollInterval());
                    }
                    return snapshot.status().equals(status);
                });
        return lastResponse.get();
    }

    /** {@link #awaitTaskStatus(String, String, String, Duration)} with a 5s timeout.
     *
     * @param sessionId the session id, or {@code null}
     * @param taskId    the task id to poll
     * @param status    the expected terminal status
     * @return the last response body, or {@code null}
     */
    public @Nullable String awaitTaskStatus(String sessionId, String taskId, String status) {
        return awaitTaskStatus(sessionId, taskId, status, Duration.ofSeconds(5));
    }

    /** {@link #awaitTaskStatus(String, String, String, Duration)} against the stored session.
     *
     * @param taskId  the task id to poll
     * @param status  the expected terminal status
     * @param timeout the maximum wait duration
     * @return the last response body, or {@code null}
     */
    public @Nullable String awaitTaskStatus(String taskId, String status, Duration timeout) {
        return awaitTaskStatus(sessionId, taskId, status, timeout);
    }

    /** {@link #awaitTaskStatus(String, String, String)} against the stored session, 5s timeout.
     *
     * @param taskId the task id to poll
     * @param status the expected terminal status
     * @return the last response body, or {@code null}
     */
    public @Nullable String awaitTaskStatus(String taskId, String status) {
        return awaitTaskStatus(taskId, status, Duration.ofSeconds(5));
    }

    /** Closes the session with {@code DELETE}.
     *
     * @param sessionId the session id to close
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> delete(String sessionId) throws Exception {
        return httpClient.send(
                baseRequest().header("MCP-Session-Id", sessionId).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Issues {@code DELETE} without a session header.
     *
     * @return the HTTP response
     * @throws Exception if the request fails
     */
    public HttpResponse<String> deleteWithoutSession() throws Exception {
        return httpClient.send(baseRequest().DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest() {
        return HttpRequest.newBuilder()
                .uri(mcpEndpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocolVersion());
    }

    private HttpRequest.Builder requestBuilder(String body) throws Exception {
        var builder = baseRequest();
        configureRequest(builder, body);
        return builder;
    }

    /** Hook: rewrites the request body before sending (e.g. 2026-07-28 {@code _meta} shaping).
     *
     * @param body the original request body
     * @return the rewritten body
     */
    protected String requestBody(String body) {
        return body;
    }

    /** Hook: adds protocol-version-specific request headers (e.g. {@code Mcp-Method}).
     *
     * @param builder the request builder to configure
     * @param body    the request body
     * @throws Exception if configuration fails
     */
    protected void configureRequest(HttpRequest.Builder builder, String body) throws Exception {}

    /**
     * Parses {@code "id":<n>} or {@code "id":"<s>"} out of a JSON-RPC request body.
     *
     * @param requestBody the JSON-RPC request body
     * @return the extracted request id as a string, or empty if not found
     */
    public static String extractRequestId(String requestBody) {
        var idx = requestBody.indexOf("\"id\"");
        if (idx < 0) return "";
        var colon = requestBody.indexOf(':', idx);
        if (colon < 0) return "";
        var sb = new StringBuilder();
        for (int i = colon + 1; i < requestBody.length(); i++) {
            var c = requestBody.charAt(i);
            if (c == ',' || c == '}') break;
            if (!Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Walks all {@code data:} lines in an SSE body and returns the JSON payload whose JSON-RPC
     * envelope has an id matching {@code requestId}. Falls back to the last data line if no match.
     *
     * @param sseBody    the SSE response body
     * @param requestId  the JSON-RPC request id to match
     * @return the matching JSON-RPC envelope
     */
    public static String extractJsonRpcResponse(String sseBody, String requestId) {
        String last = null;
        var expectedId = requestId.isEmpty() ? null : MAPPER.readTree(requestId);
        for (var line : sseBody.split("\n")) {
            String data = null;
            if (line.startsWith("data: ")) data = line.substring("data: ".length());
            else if (line.startsWith("data:")) data = line.substring("data:".length());
            if (data == null) continue;
            last = data;
            if (expectedId != null && MAPPER.readTree(data).path("id").equals(expectedId)) {
                return data;
            }
        }
        assertThat(last).as("SSE response must contain at least one data line").isNotNull();
        return last;
    }

    private static TaskSnapshot taskSnapshot(String json, String taskId) {
        var response = MAPPER.readTree(json);
        assertThat(response.path("id").asString())
                .as("tasks/get response id in: %s", json)
                .isEqualTo("tasks-get");
        var result = response.path("result");
        assertThat(result.isObject()).as("tasks/get result in: %s", json).isTrue();
        assertThat(result.path("taskId").asString())
                .as("tasks/get taskId in: %s", json)
                .isEqualTo(taskId);
        var status = result.path("status").asString(null);
        assertThat(status).as("tasks/get status in: %s", json).isNotNull();
        var pollIntervalNode = result.path("pollInterval");
        var pollInterval = pollIntervalNode.isNumber() && pollIntervalNode.asLong() > 0
                ? Duration.ofMillis(pollIntervalNode.asLong())
                : null;
        return new TaskSnapshot(status, pollInterval);
    }

    private record TaskSnapshot(String status, @Nullable Duration pollInterval) {}
}
