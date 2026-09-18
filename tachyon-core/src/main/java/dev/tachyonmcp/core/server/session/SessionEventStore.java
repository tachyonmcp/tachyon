/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Persists and replays session events (request/responses, notifications). */
@ExperimentalApi(since = "1.0.0-beta.26")
public interface SessionEventStore extends Closeable {
    /** Appends an event to the log. */
    void append(SessionEvent event);

    /**
     * Drains events to the processor one at a time until exhausted or backpressured; returns the
     * last cursor.
     */
    long drain(String sessionId, long cursor, Predicate<SessionEvent> processor);

    /** Returns all events for the session with sequence number greater than {@code lastSeq}. */
    default List<SessionEvent> replay(String sessionId, long lastSeq) {
        var out = new ArrayList<SessionEvent>();
        drain(sessionId, lastSeq, event -> {
            out.add(event);
            return true;
        });
        return List.copyOf(out);
    }

    /**
     * Returns the log used when sessions are disabled: it retains nothing, so nothing can be replayed.
     *
     * @return the shared no-op log, never {@code null}
     */
    static SessionEventStore noop() {
        return NoopSessionEventStore.INSTANCE;
    }
}

/**
 * Event log for a stateless server. A stateless server never opens a resumable stream, so there is
 * nothing to replay and appended events are discarded.
 */
final class NoopSessionEventStore implements SessionEventStore {

    static final SessionEventStore INSTANCE = new NoopSessionEventStore();

    private NoopSessionEventStore() {}

    @Override
    public void append(SessionEvent event) {}

    @Override
    public long drain(String sessionId, long cursor, Predicate<SessionEvent> processor) {
        return cursor;
    }

    @Override
    public void close() {}
}
