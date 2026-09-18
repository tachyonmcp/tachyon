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
 * Session lifecycle and persistence configuration: the sessions a stateful server keeps.
 *
 * <p>Stateless is a property of the server, not of a session — a stateless server simply has no
 * session configuration. Configuring any option on {@link Builder} turns sessions on;
 * {@link Builder#enabled()} turns them on with the defaults, and
 * {@link dev.tachyonmcp.core.server.ServerBuilder#stateless()} states the opt-out.
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

    /** Rejection message for session options supplied while sessions are disabled. */
    public static final String SESSION_OPTIONS_REQUIRE_ENABLED =
            "Session options require sessions to be enabled — call enabled(true)";

    public static final SessionConfig STATELESS = new SessionConfig(false, null, null, null, null, null);

    public SessionConfig {
        if (!enabled) {
            if (sessionIdGenerator != null
                    || sessionStore != null
                    || sessionEventStore != null
                    || sessionTtl != null
                    || janitorInterval != null) {
                throw new IllegalStateException(SESSION_OPTIONS_REQUIRE_ENABLED);
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
     * Returns {@link #sessionEventStore()}, or {@link SessionEventStore#noop()} when sessions are
     * disabled — a stateless server retains no events.
     *
     * @return the event store to use, never {@code null}
     */
    @ExperimentalApi(since = "1.0.0-beta.26")
    public SessionEventStore sessionEventStoreOrDefault() {
        return sessionEventStore != null ? sessionEventStore : SessionEventStore.noop();
    }

    /**
     * Returns {@link #sessionStore()}, or {@link SessionStore#noop()} when sessions are disabled — a
     * stateless server persists no snapshots.
     *
     * @return the session store to use, never {@code null}
     */
    @ExperimentalApi(since = "1.0.0-beta.26")
    public SessionStore sessionStoreOrDefault() {
        return sessionStore != null ? sessionStore : SessionStore.noop();
    }

    /**
     * Creates a session builder. The builder starts stateless; configuring any session option turns
     * sessions on.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a {@link SessionConfig}.
     *
     * <p>Configuring a session <em>is</em> the opt-in: {@link #sessionTtl(Duration)},
     * {@link #janitorInterval(Duration)}, {@link #sessionStore(SessionStore)},
     * {@link #sessionEventStore(SessionEventStore)} and
     * {@link #sessionIdGenerator(SessionIdGenerator)} each turn sessions on, so no separate flag is
     * needed. {@link #enabled()} turns them on with the defaults, and an untouched builder is
     * stateless — the opt-out needs no call, and
     * {@link dev.tachyonmcp.core.server.ServerBuilder#stateless()} states it at the server.
     *
     * <p>"Stateless with session options" is the one contradiction this builder can express, and
     * only through the deprecated {@link #enabled(boolean)}; it fails at {@link #build()} with
     * {@link #SESSION_OPTIONS_REQUIRE_ENABLED}.
     */
    public static final class Builder {

        private @Nullable Boolean enabled;
        private @Nullable Duration sessionTtl;
        private @Nullable Duration janitorInterval;
        private @Nullable SessionEventStore sessionEventStore;
        private @Nullable SessionStore sessionStore;
        private @Nullable SessionIdGenerator<? super HttpRequest> sessionIdGenerator;

        private Builder() {}

        /**
         * Enables server-side sessions with the default options. Redundant when a session option is
         * configured — that already enables them.
         *
         * @return this builder
         */
        public Builder enabled() {
            this.enabled = Boolean.TRUE;
            return this;
        }

        /**
         * Enables or disables server-side sessions.
         *
         * @param enabled whether sessions are enabled
         * @return this builder
         * @deprecated Use {@link #enabled()}, or simply configure a session option. For the
         *     explicit opt-out use {@link dev.tachyonmcp.core.server.ServerBuilder#stateless()}
         *     — a boolean sitting next to the options it contradicts is the only way to build an
         *     invalid configuration.
         */
        @Deprecated(forRemoval = true)
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the session TTL (idle sessions are evicted after this duration) and enables sessions.
         *
         * @param sessionTtl the idle timeout
         * @return this builder
         */
        public Builder sessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
            return this;
        }

        /**
         * Sets the janitor sweep interval and enables sessions.
         *
         * @param janitorInterval the interval between sweeps
         * @return this builder
         */
        public Builder janitorInterval(Duration janitorInterval) {
            this.janitorInterval = janitorInterval;
            return this;
        }

        /**
         * Sets a custom session event store and enables sessions.
         *
         * @param store the event store; {@code null} leaves it unset, so enabled sessions use the
         *     default in-memory log
         * @return this builder
         */
        @ExperimentalApi(since = "1.0.0-beta.26")
        public Builder sessionEventStore(@Nullable SessionEventStore store) {
            this.sessionEventStore = store;
            return this;
        }

        /**
         * Sets a custom immutable session snapshot store and enables sessions.
         *
         * @param store the snapshot store; {@code null} leaves it unset, so enabled sessions use the
         *     default in-memory store
         * @return this builder
         */
        @ExperimentalApi(since = "1.0.0-beta.26")
        public Builder sessionStore(@Nullable SessionStore store) {
            this.sessionStore = store;
            return this;
        }

        /**
         * Sets a custom session id generator (derives the id from the initialize request) and
         * enables sessions.
         *
         * @param generator the generator; {@code null} leaves it unset, so enabled sessions use
         *     {@link SessionIdGenerator#DEFAULT}
         * @return this builder
         */
        public Builder sessionIdGenerator(@Nullable SessionIdGenerator<? super HttpRequest> generator) {
            this.sessionIdGenerator = generator;
            return this;
        }

        /**
         * Builds the {@link SessionConfig}.
         *
         * @return the configuration; {@link SessionConfig#STATELESS} unless sessions were enabled or
         *     a session option was configured
         * @throws IllegalStateException when a session option was configured while sessions were
         *     explicitly disabled
         */
        public SessionConfig build() {
            var configured = sessionTtl != null
                    || janitorInterval != null
                    || sessionEventStore != null
                    || sessionStore != null
                    || sessionIdGenerator != null;

            if (Boolean.FALSE.equals(enabled)) {
                if (configured) {
                    throw new IllegalStateException(SESSION_OPTIONS_REQUIRE_ENABLED);
                }
                return STATELESS;
            }

            if (!configured && !Boolean.TRUE.equals(enabled)) {
                return STATELESS;
            }

            return new SessionConfig(
                    true, sessionTtl, sessionEventStore, sessionStore, sessionIdGenerator, janitorInterval);
        }
    }
}
