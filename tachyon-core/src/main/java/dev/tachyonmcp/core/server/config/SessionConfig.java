/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.server.session.InMemorySessionEventStore;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.core.server.session.SessionEventStore;
import dev.tachyonmcp.core.server.session.SessionStore;
import io.netty.handler.codec.http.HttpRequest;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Session lifecycle and persistence configuration.
 *
 * @param enabled            when {@code false} (the default) the server is stateless: no session is
 *                           created and no TTL tracking occurs; set {@code true} to enable sessions
 * @param sessionTtl         duration after which idle sessions are evicted (default 30s)
 * @param sessionEventStore  custom event store; when enabled, {@code null} becomes in-memory
 * @param sessionStore       custom session store; when enabled, {@code null} becomes in-memory
 * @param sessionIdGenerator session id generator; defaults to {@link SessionIdGenerator#DEFAULT}
 * @param janitorInterval    interval between janitor sweeps (default 5s);
 *                           {@code null} uses the default
 */
public record SessionConfig(
        boolean enabled,
        @Nullable Duration sessionTtl,
        @ExperimentalApi(since = "1.0.0-beta.26") @Nullable SessionEventStore sessionEventStore,
        @ExperimentalApi(since = "1.0.0-beta.26") @Nullable SessionStore sessionStore,
        @Nullable SessionIdGenerator<? super HttpRequest> sessionIdGenerator,
        @Nullable Duration janitorInterval) {

    public static final Duration DEFAULT_SESSION_TTL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_JANITOR_INTERVAL = Duration.ofSeconds(5);

    public static final SessionConfig STATELESS = new SessionConfig(false, null, null, null, null, null);

    public SessionConfig {
        if (!enabled) {
            if (sessionIdGenerator != null
                    || sessionStore != null
                    || sessionEventStore != null
                    || sessionTtl != null
                    || janitorInterval != null) {
                throw new IllegalStateException("Session options require sessions to be enabled — call enabled(true)");
            }
        } else {
            if (sessionTtl == null) sessionTtl = DEFAULT_SESSION_TTL;
            if (janitorInterval == null) janitorInterval = DEFAULT_JANITOR_INTERVAL;
            if (sessionIdGenerator == null) sessionIdGenerator = SessionIdGenerator.DEFAULT;
            if (sessionEventStore == null) sessionEventStore = new InMemorySessionEventStore();
            if (sessionStore == null) sessionStore = new InMemorySessionStore();
        }
    }

    /**
     * Returns {@link #sessionEventStore()}, or a new in-memory store when sessions are disabled.
     *
     * @return the event store to use, never {@code null}
     */
    @ExperimentalApi(since = "1.0.0-beta.26")
    public SessionEventStore sessionEventStoreOrDefault() {
        return sessionEventStore != null ? sessionEventStore : new InMemorySessionEventStore();
    }

    /**
     * Returns {@link #sessionStore()}, or a new in-memory store when sessions are disabled.
     *
     * @return the session store to use, never {@code null}
     */
    @ExperimentalApi(since = "1.0.0-beta.26")
    public SessionStore sessionStoreOrDefault() {
        return sessionStore != null ? sessionStore : new InMemorySessionStore();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link SessionConfig}.
     */
    public static final class Builder {
        private boolean enabled = false;
        private @Nullable Duration sessionTtl;
        private @Nullable Duration janitorInterval;
        private @Nullable SessionEventStore sessionEventStore;
        private @Nullable SessionStore sessionStore;
        private @Nullable SessionIdGenerator<? super HttpRequest> sessionIdGenerator;

        private Builder() {}

        /**
         * Enables server-side sessions. Off by default (the server is stateless), so callers opt in
         * explicitly with {@code enabled(true)}.
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the session TTL (idle sessions are evicted after this duration).
         */
        public Builder sessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
            return this;
        }

        /**
         * Sets the janitor sweep interval.
         */
        public Builder janitorInterval(Duration janitorInterval) {
            this.janitorInterval = janitorInterval;
            return this;
        }

        /**
         * Sets a custom session event store.
         */
        @ExperimentalApi(since = "1.0.0-beta.26")
        public Builder sessionEventStore(@Nullable SessionEventStore store) {
            this.sessionEventStore = store;
            return this;
        }

        /**
         * Sets a custom immutable session snapshot store.
         */
        @ExperimentalApi(since = "1.0.0-beta.26")
        public Builder sessionStore(@Nullable SessionStore store) {
            this.sessionStore = store;
            return this;
        }

        /**
         * Sets a custom session id generator (derives the id from the initialize request).
         * {@code null} restores {@link SessionIdGenerator#DEFAULT}.
         */
        public Builder sessionIdGenerator(@Nullable SessionIdGenerator<? super HttpRequest> generator) {
            this.sessionIdGenerator = generator;
            return this;
        }

        /**
         * Builds the {@link SessionConfig}.
         */
        public SessionConfig build() {
            if (!enabled) {
                if (sessionIdGenerator != null
                        || sessionStore != null
                        || sessionEventStore != null
                        || sessionTtl != null
                        || janitorInterval != null) {
                    throw new IllegalStateException(
                            "Session options require sessions to be enabled — call enabled(true)");
                }

                return SessionConfig.STATELESS;
            } else {
                return new SessionConfig(
                        enabled, sessionTtl, sessionEventStore, sessionStore, sessionIdGenerator, janitorInterval);
            }
        }
    }
}
