/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The lines of an SSE response as they arrive, and the moment the server ended the stream. A stream
 * the server closed gracefully and one it dropped both count as ended; what a test needs is when.
 * Close it when the test is done with a stream still open: a JDK {@code HttpClient} will not close
 * while a response is.
 */
public final class SseLines implements AutoCloseable {

    private final Stream<String> body;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> ended;
    private volatile @Nullable Instant endedAt;

    public SseLines(HttpResponse<Stream<String>> response) {
        body = response.body();
        ended = CompletableFuture.runAsync(
                () -> {
                    try (body) {
                        body.forEach(lines::add);
                    } finally {
                        endedAt = Instant.now();
                    }
                },
                task -> Thread.ofVirtual().start(task));
    }

    /** Whether any line received so far contains {@code text}. */
    public boolean contains(String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    /** Whether the server has ended the stream. */
    public boolean ended() {
        return ended.isDone();
    }

    /** When the stream ended, as this client saw it: never earlier than the server closed it. */
    public Instant endedAt() {
        final var at = endedAt;
        if (at == null) {
            throw new IllegalStateException("The stream has not ended");
        }
        return at;
    }

    /** The value of the last {@code id:} line received, which a client resumes from. */
    public String lastEventId() {
        for (int i = lines.size() - 1; i >= 0; i--) {
            final var line = lines.get(i);
            if (line.startsWith("id:")) {
                return line.substring(3).strip();
            }
        }
        throw new IllegalStateException("No event id was received");
    }

    @Override
    public String toString() {
        return (ended() ? "ended" : "open") + " " + lines;
    }

    /** Drops the connection from the client's side. */
    @Override
    public void close() {
        body.close();
    }
}
