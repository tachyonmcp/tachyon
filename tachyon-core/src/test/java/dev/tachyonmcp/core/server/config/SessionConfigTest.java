/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.server.session.InMemorySessionEventStore;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.server.session.SessionEventStore;
import dev.tachyonmcp.core.server.session.SessionKey;
import dev.tachyonmcp.core.server.session.SessionSnapshot;
import dev.tachyonmcp.core.server.session.SessionStore;
import io.netty.handler.codec.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link SessionConfig} defaults (stateless, default id generator, no stores) and the
 * fail-fast rejection of session options configured while sessions are disabled — compact
 * constructor resolves value defaults ({@code sessionTtl}, {@code janitorInterval},
 * {@code sessionIdGenerator}) when {@code enabled=true}.
 *
 * @author Konstantin Pavlov
 */
class SessionConfigTest {

    @Test
    void defaultsAreStateless() {
        var config = SessionConfig.builder().build();

        assertThat(config.enabled()).isFalse();
        assertThat(config.sessionIdGenerator()).isNull();
        assertThat(config.sessionStore()).isNull();
        assertThat(config.sessionEventStore()).isNull();
    }

    @Test
    void resolvesDefaultTtlWhenEnabled() {
        var config = SessionConfig.builder().enabled().build();

        assertThat(config.enabled()).isTrue();
        assertThat(config.sessionTtl()).isEqualTo(SessionConfig.DEFAULT_SESSION_TTL);
    }

    @Test
    void resolvesDefaultJanitorIntervalWhenEnabled() {
        var config = SessionConfig.builder().enabled().build();

        assertThat(config.janitorInterval()).isEqualTo(SessionConfig.DEFAULT_JANITOR_INTERVAL);
    }

    @Test
    void resolvesDefaultSessionIdGeneratorWhenEnabled() {
        var config = SessionConfig.builder().enabled().build();

        assertThat(config.sessionIdGenerator()).isSameAs(SessionIdGenerator.DEFAULT);
    }

    @Test
    @SuppressWarnings("removal")
    void sessionOptionsWhileDisabledFailFast() {
        SessionIdGenerator<HttpRequest> custom = (channelContext, request) -> "custom";

        final var expectedMessage = "Session options require sessions to be enabled — call enabled()";

        assertThatIllegalStateException()
                .isThrownBy(() -> SessionConfig.builder()
                        .enabled(false)
                        .sessionIdGenerator(custom)
                        .build())
                .withMessage(expectedMessage);

        assertThatIllegalStateException()
                .isThrownBy(() -> SessionConfig.builder()
                        .enabled(false)
                        .sessionStore(new InMemorySessionStore())
                        .build())
                .withMessage(expectedMessage);

        assertThatIllegalStateException()
                .isThrownBy(() -> SessionConfig.builder()
                        .enabled(false)
                        .sessionEventStore(new InMemorySessionEventStore())
                        .build())
                .withMessage(expectedMessage);

        assertThatIllegalStateException()
                .isThrownBy(() -> SessionConfig.builder()
                        .enabled(false)
                        .sessionTtl(Duration.ofSeconds(42))
                        .build())
                .withMessage(expectedMessage);
    }

    @Test
    void configuringAnyOptionEnablesSessions() {
        SessionIdGenerator<HttpRequest> custom = (channelContext, request) -> "custom";
        var ttl = Duration.ofSeconds(42);

        var byTtl = SessionConfig.builder().sessionTtl(ttl).build();

        assertThat(byTtl.enabled()).isTrue();
        assertThat(byTtl.sessionTtl()).isEqualTo(ttl);
        assertThat(byTtl.janitorInterval()).isEqualTo(SessionConfig.DEFAULT_JANITOR_INTERVAL);
        assertThat(byTtl.sessionIdGenerator()).isSameAs(SessionIdGenerator.DEFAULT);
        assertThat(byTtl.sessionStore()).isInstanceOf(InMemorySessionStore.class);

        assertThat(SessionConfig.builder()
                        .janitorInterval(Duration.ofSeconds(1))
                        .build()
                        .enabled())
                .isTrue();
        assertThat(SessionConfig.builder().sessionIdGenerator(custom).build().enabled())
                .isTrue();
        assertThat(SessionConfig.builder()
                        .sessionStore(new InMemorySessionStore())
                        .build()
                        .enabled())
                .isTrue();
        assertThat(SessionConfig.builder()
                        .sessionEventStore(new InMemorySessionEventStore())
                        .build()
                        .enabled())
                .isTrue();
    }

    @Test
    void nullOptionsDoNotEnableSessions() {
        var config = SessionConfig.builder()
                .sessionStore(null)
                .sessionEventStore(null)
                .sessionIdGenerator(null)
                .build();

        assertThat(config).isSameAs(SessionConfig.STATELESS);
        assertThat(config.enabled()).isFalse();
    }

    @Test
    @SuppressWarnings("removal")
    void explicitlyDisabledWithoutOptionsIsStateless() {
        assertThat(SessionConfig.builder().enabled(false).build()).isSameAs(SessionConfig.STATELESS);
    }

    @Test
    @SuppressWarnings("removal")
    void disablingAfterConfiguringAnOptionFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SessionConfig.builder()
                        .sessionTtl(Duration.ofSeconds(42))
                        .enabled(false)
                        .build())
                .withMessage(SessionConfig.SESSION_OPTIONS_REQUIRE_ENABLED);
    }

    @Test
    @SuppressWarnings("removal")
    void reEnablingAfterDisablingKeepsOptions() {
        var ttl = Duration.ofSeconds(42);

        var config =
                SessionConfig.builder().enabled(false).enabled().sessionTtl(ttl).build();

        assertThat(config.enabled()).isTrue();
        assertThat(config.sessionTtl()).isEqualTo(ttl);
    }

    @Test
    void sessionOptionsWithEnabledAreAccepted() {
        SessionIdGenerator<HttpRequest> custom = (channelContext, request) -> "custom";

        var config = SessionConfig.builder()
                .enabled()
                .sessionIdGenerator(custom)
                .sessionStore(new InMemorySessionStore())
                .build();

        assertThat(config.enabled()).isTrue();
        assertThat(config.sessionIdGenerator()).isSameAs(custom);
        assertThat(config.sessionStore()).isNotNull();
    }

    @Test
    void recordRejectsSessionOptionsWhileDisabled() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new SessionConfig(false, Duration.ofSeconds(42), null, null, null, null))
                .withMessage(SessionConfig.SESSION_OPTIONS_REQUIRE_ENABLED);

        assertThatIllegalStateException()
                .isThrownBy(() -> new SessionConfig(false, null, null, new InMemorySessionStore(), null, null))
                .withMessage(SessionConfig.SESSION_OPTIONS_REQUIRE_ENABLED);
    }

    @Test
    void statelessResolvesNoopStores() {
        var config = SessionConfig.builder().build();

        assertThat(config.sessionStoreOrDefault()).isSameAs(SessionStore.noop());
        assertThat(config.sessionEventStoreOrDefault()).isSameAs(SessionEventStore.noop());
    }

    @Test
    void enabledResolvesInMemoryStoresAndKeepsThemStable() {
        var config = SessionConfig.builder().enabled().build();

        assertThat(config.sessionStoreOrDefault())
                .isInstanceOf(InMemorySessionStore.class)
                .isSameAs(config.sessionStore())
                .isSameAs(config.sessionStoreOrDefault());
        assertThat(config.sessionEventStoreOrDefault())
                .isInstanceOf(InMemorySessionEventStore.class)
                .isSameAs(config.sessionEventStore())
                .isSameAs(config.sessionEventStoreOrDefault());
    }

    @Test
    void noopStoreAcceptsWritesWithoutPersistingThem() {
        var store = SessionStore.noop();
        var key = new SessionKey("sess_1", "gen_1");
        var expiresAt = Instant.now().plusSeconds(30);

        var snapshot = store.create(key, expiresAt);

        assertThat(snapshot.key()).isEqualTo(key);
        assertThat(snapshot.state()).isEqualTo(SessionState.INITIALIZING);
        assertThat(store.find("sess_1")).isEmpty();
        assertThat(store.touch(key, expiresAt)).isTrue();
        assertThat(store.compareAndSet(
                        snapshot, new SessionSnapshot(key, SessionState.ACTIVE, null, Set.of(), null, expiresAt, 1)))
                .isTrue();
        assertThat(store.terminate(key)).isTrue();
    }

    @Test
    void noopEventStoreRetainsNothing() {
        var store = SessionEventStore.noop();

        store.append(new SessionEvent.ResponseEvent("sess_1", RequestId.of(1), "{}", 1L, -1, null));

        assertThat(store.replay("sess_1", -1)).isEmpty();
        assertThat(store.drain("sess_1", 7L, event -> true)).isEqualTo(7L);
    }
}
