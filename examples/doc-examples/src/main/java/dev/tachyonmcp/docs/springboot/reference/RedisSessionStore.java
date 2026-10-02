package dev.tachyonmcp.docs.springboot.reference;

import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.core.server.session.SessionKey;
import dev.tachyonmcp.core.server.session.SessionSnapshot;
import dev.tachyonmcp.core.server.session.SessionStore;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class RedisSessionStore implements SessionStore {

    private final SessionStore delegate = new InMemorySessionStore();
    private final AtomicInteger created = new AtomicInteger();

    public int created() {
        return created.get();
    }

    @Override
    public SessionSnapshot create(SessionKey key, Instant expiresAt) {
        created.incrementAndGet();
        return delegate.create(key, expiresAt);
    }

    @Override
    public Optional<SessionSnapshot> find(String sessionId) {
        return delegate.find(sessionId);
    }

    @Override
    public boolean compareAndSet(SessionSnapshot expected, SessionSnapshot updated) {
        return delegate.compareAndSet(expected, updated);
    }

    @Override
    public boolean touch(SessionKey key, Instant expiresAt) {
        return delegate.touch(key, expiresAt);
    }

    @Override
    public boolean terminate(SessionKey key) {
        return delegate.terminate(key);
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
