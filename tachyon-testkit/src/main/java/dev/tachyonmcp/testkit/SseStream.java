/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.net.ssl.SSLSocketFactory;
import me.kpavlov.finchly.queue.MessageAggregator;
import me.kpavlov.finchly.queue.QueueSubscriber;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;

/**
 * A background SSE frame stream used for both raw-socket GET subscriptions and streaming POST
 * responses.
 *
 * <p>The raw-socket mode supports reconnect / {@code Last-Event-ID} scenarios where {@link
 * java.net.http.HttpClient}'s higher-level body handling does not provide enough control. POST mode
 * consumes the response returned by {@link McpClient#sendStreamingRequest(String, String)}. Both
 * modes deliver parsed frames through the same {@link QueueSubscriber} API.
 */
public final class SseStream extends QueueSubscriber<SseFrame> implements AutoCloseable {

    private static final Pattern ID_LINE = Pattern.compile("id:\\s?(.*)");
    private static final Pattern EVENT_LINE = Pattern.compile("event:\\s?(.*)");
    private static final Pattern DATA_LINE = Pattern.compile("data:\\s?(.*)");

    private final @Nullable URI endpoint;
    private final @Nullable String sessionId;
    private final @Nullable String lastEventId;
    private final @Nullable String protocolVersion;
    private final @Nullable HttpResponse<Stream<String>> response;

    private @Nullable Socket socket;
    private @Nullable CompletableFuture<Void> responseConsumer;
    private final Queue<String> rawChunks = new ConcurrentLinkedQueue<>();
    private volatile boolean stopped;

    /**
     * Opens against {@code endpoint}, with its own {@link MessageAggregator}.
     */
    SseStream(URI endpoint, String sessionId, @Nullable String lastEventId, String protocolVersion) {
        this(endpoint, sessionId, lastEventId, protocolVersion, new MessageAggregator<>());
    }

    SseStream(
            URI endpoint,
            String sessionId,
            @Nullable String lastEventId,
            String protocolVersion,
            MessageAggregator<SseFrame> aggregator) {
        super(aggregator);
        this.endpoint = endpoint;
        this.sessionId = sessionId;
        this.lastEventId = lastEventId;
        this.protocolVersion = protocolVersion;
        this.response = null;
    }

    SseStream(HttpResponse<Stream<String>> response) {
        super(new MessageAggregator<>());
        this.endpoint = null;
        this.sessionId = null;
        this.lastEventId = null;
        this.protocolVersion = null;
        this.response = Objects.requireNonNull(response, "response cannot be null");
    }

    /** Starts consuming and parsing frames from the configured GET or POST stream. */
    @Override
    public void start() {
        if (response != null) {
            responseConsumer = CompletableFuture.runAsync(
                    this::readResponse, command -> Thread.ofVirtual().start(command));
            return;
        }
        try {
            var endpoint = Objects.requireNonNull(this.endpoint);
            var host = endpoint.getHost();
            var port = endpoint.getPort() != -1 ? endpoint.getPort() : defaultPort(endpoint);
            socket = "https".equalsIgnoreCase(endpoint.getScheme())
                    ? SSLSocketFactory.getDefault().createSocket(host, port)
                    : new Socket(host, port);
            var path = endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath();
            var req = new StringBuilder("GET ")
                    .append(path)
                    .append(" HTTP/1.1\r\n")
                    .append("Host: ")
                    .append(host)
                    .append(':')
                    .append(port)
                    .append("\r\n")
                    .append("MCP-Session-Id: ")
                    .append(Objects.requireNonNull(sessionId))
                    .append("\r\n")
                    .append("MCP-Protocol-Version: ")
                    .append(Objects.requireNonNull(protocolVersion))
                    .append("\r\n")
                    .append("Accept: text/event-stream\r\n");
            if (lastEventId != null) {
                req.append("Last-Event-ID: ").append(lastEventId).append("\r\n");
            }
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.setSoTimeout(50);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open SSE stream to " + endpoint, e);
        }
        Thread.ofVirtual().start(this::readLoop);
    }

    private static int defaultPort(URI endpoint) {
        return "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
    }

    /** Closes the response or socket and stops the background reader. */
    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (response != null) {
            response.body().close();
            awaitResponseConsumer();
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort close; nothing more to do with a socket we're discarding.
            }
        }
    }

    /** Stops the background reader and closes its source, same as {@link #stop()}. */
    @Override
    public void close() {
        stop();
    }

    /**
     * Awaits an SSE frame matching {@code predicate}, or fails after {@code timeout}.
     *
     * @param predicate the predicate to match
     * @param timeout   the maximum wait duration
     * @return the matching frame
     */
    public SseFrame await(Predicate<SseFrame> predicate, Duration timeout) {
        return aggregator.awaitMessage(timeout, false, predicate);
    }

    /**
     * Awaits the first frame carrying an eventType id — the priming eventType a fresh GET stream sends.
     *
     * @param timeout the maximum wait duration
     * @return the first event id
     */
    public String awaitFirstEventId(Duration timeout) {
        return Objects.requireNonNull(await(f -> f.id() != null, timeout).id());
    }

    /**
     * Waits out {@code window} and asserts no frame matching {@code predicate} arrived in it.
     * Unlike {@link #await}, absence can't be confirmed faster than waiting the full window —
     * this isn't a lazy sleep standing in for a pollable condition, it <em>is</em> the condition.
     *
     * @param predicate the predicate to check for absence
     * @param window    the time window to wait
     */
    public void assertNoneArrived(Predicate<SseFrame> predicate, Duration window) {
        try {
            Thread.sleep(window);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        var unexpected = aggregator.findAll(predicate);
        if (!unexpected.isEmpty()) {
            throw new AssertionError("Expected no matching SSE frame within " + window + ", but got: " + unexpected);
        }
    }

    /**
     * All frames delivered so far matching {@code predicate}, without waiting.
     *
     * @param predicate the predicate to filter by
     * @return the matching frames
     */
    public List<SseFrame> received(Predicate<SseFrame> predicate) {
        return aggregator.findAll(predicate);
    }

    /**
     * The raw response bytes accumulated so far in raw-socket GET mode, as text.
     *
     * @return the raw response text
     */
    public String rawResponse() {
        return String.join("", rawChunks);
    }

    /**
     * Polls {@link #rawResponse()} in raw-socket GET mode until {@code predicate} holds, or fails
     * after {@code timeout}. This supports plain responses that never deliver an SSE frame.
     *
     * @param predicate the predicate to match against the raw response
     * @param timeout   the maximum wait duration
     * @return the raw response text
     */
    public String awaitRawResponse(Predicate<String> predicate, Duration timeout) {
        return Awaitility.await()
                .atMost(timeout)
                .pollDelay(Duration.ofMillis(20))
                .pollInterval(Duration.ofMillis(50))
                .until(this::rawResponse, predicate);
    }

    private void readLoop() {
        var socket = Objects.requireNonNull(this.socket, "start() must run before the reader thread");
        var buf = new byte[1024];
        var lineBuf = new StringBuilder();
        var parser = new FrameParser();
        while (!stopped) {
            try {
                var n = socket.getInputStream().read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                var chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                rawChunks.add(chunk);
                lineBuf.append(chunk);
                int newline;
                while ((newline = lineBuf.indexOf("\n")) >= 0) {
                    var line = lineBuf.substring(0, newline).stripTrailing();
                    lineBuf.delete(0, newline + 1);
                    parser.accept(line);
                }
            } catch (SocketTimeoutException e) {
                // No data this poll; keep reading until stopped, or the socket closes/errors.
            } catch (IOException e) {
                break;
            }
        }
    }

    private void readResponse() {
        var response = Objects.requireNonNull(this.response);
        var parser = new FrameParser();
        try (var lines = response.body()) {
            lines.forEach(parser::accept);
            parser.finish();
        }
    }

    private void awaitResponseConsumer() {
        var consumer = responseConsumer;
        if (consumer == null) {
            return;
        }
        try {
            consumer.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (!(cause instanceof IOException) && !(cause instanceof UncheckedIOException)) {
                throw new IllegalStateException("SSE response consumer failed", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing SSE response", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out while closing SSE response", e);
        }
    }

    private final class FrameParser {
        private @Nullable String pendingId;
        private @Nullable String pendingEvent;
        private @Nullable StringBuilder pendingData;

        void accept(String line) {
            if (line.isEmpty()) {
                emit();
                return;
            }
            var idMatcher = ID_LINE.matcher(line);
            var eventMatcher = EVENT_LINE.matcher(line);
            var dataMatcher = DATA_LINE.matcher(line);
            if (idMatcher.matches()) {
                pendingId = idMatcher.group(1);
            } else if (eventMatcher.matches()) {
                pendingEvent = eventMatcher.group(1);
            } else if (dataMatcher.matches()) {
                if (pendingData == null) {
                    pendingData = new StringBuilder();
                } else {
                    pendingData.append('\n');
                }
                pendingData.append(dataMatcher.group(1));
            }
        }

        void finish() {
            emit();
        }

        private void emit() {
            if (pendingData != null) {
                deliver(new SseFrame(pendingId, pendingEvent, pendingData.toString()));
            }
            pendingId = null;
            pendingEvent = null;
            pendingData = null;
        }
    }
}
