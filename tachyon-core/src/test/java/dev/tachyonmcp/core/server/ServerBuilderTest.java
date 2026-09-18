/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.domain.UriTemplateValue;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.completions.AsyncCompletionFn;
import dev.tachyonmcp.api.server.features.completions.CompletionFn;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.server.session.SessionEventStore;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies {@link ServerBuilder} configures a server-owned virtual-thread-per-task executor for
 * blocking-first dispatch.
 *
 * @author Konstantin Pavlov
 */
class ServerBuilderTest {

    @Test
    void exposesBuilderAsInterface() {
        assertThat(TachyonServer.builder()).isInstanceOf(DefaultServerBuilder.class);
    }

    @Test
    void acceptsVirtualThreadPerTaskExecutor() {
        try (var server = TachyonServer.builder()
                .threadFactory(Thread.ofVirtual().name("test-", 0).factory())
                .build()) {
            assertThat(server).isNotNull();
        }
    }

    @Test
    void acceptsDefaultExecutor() {
        try (var server = TachyonServer.builder().build()) {
            assertThat(server).isNotNull();
        }
    }

    @Test
    void registersFeaturesThroughFacades() {
        try (var server = TachyonServer.builder().build()) {
            server.tools().register(tool -> tool.name("sync-tool"), (ctx, request) -> ToolResult.empty());
            server.resources()
                    .register(
                            resource -> resource.name("sync-resource").uri("test://sync"),
                            (ctx, request) -> TextResourceContents.of(request.uri(), "sync", "text/plain"))
                    .registerTemplate(
                            template -> template.name("sync-template").uriTemplate("test://sync/{id}"),
                            (ctx, request) -> TextResourceContents.of(
                                    request.uri(),
                                    ((UriTemplateValue.Scalar) request.params().get("id")).value(),
                                    "text/plain"));
            server.prompts().register(prompt -> prompt.name("sync-prompt"), List.of(PromptMessage.user("sync")));

            assertThat(server.tools().find("sync-tool")).isPresent();
            assertThat(server.resources().find("sync-resource")).isPresent();
            assertThat(server.resources().findTemplate("sync-template")).isPresent();
            assertThat(server.prompts().find("sync-prompt")).isPresent();
        }
    }

    @Test
    void acceptsAsyncHandlersWithoutCasts() {
        try (var server = TachyonServer.builder().build()) {
            server.tools()
                    .registerAsync(
                            tool -> tool.name("async-tool"),
                            (ctx, request) -> CompletableFuture.completedFuture(ToolResult.empty()));
            server.resources()
                    .registerAsync(
                            resource -> resource.name("async-resource").uri("test://async"),
                            (ctx, request) -> CompletableFuture.completedFuture(
                                    TextResourceContents.of(request.uri(), "async", "text/plain")))
                    .registerTemplateAsync(
                            template -> template.name("async-template").uriTemplate("test://async/{id}"),
                            (ctx, request) -> CompletableFuture.completedFuture(TextResourceContents.of(
                                    request.uri(),
                                    ((UriTemplateValue.Scalar) request.params().get("id")).value(),
                                    "text/plain")));
            server.prompts()
                    .registerAsync(
                            prompt -> prompt.name("async-prompt"),
                            (ctx, request) -> CompletableFuture.completedFuture(
                                    PromptResult.messages(List.of(PromptMessage.user("async")))));

            assertThat(server.tools().find("async-tool")).isPresent();
            assertThat(server.resources().find("async-resource")).isPresent();
            assertThat(server.resources().findTemplate("async-template")).isPresent();
            assertThat(server.prompts().find("async-prompt")).isPresent();
        }
    }

    @Test
    void registersBootstrapFeaturesThroughFacades() {
        try (var server = TachyonServer.builder()
                .withTools(tools ->
                        tools.register(tool -> tool.name("bootstrap-tool"), (ctx, request) -> ToolResult.empty()))
                .withResources(resources -> resources.register(
                        resource -> resource.name("bootstrap-resource").uri("test://bootstrap"),
                        (ctx, request) -> TextResourceContents.of(request.uri(), "bootstrap", "text/plain")))
                .withPrompts(prompts -> prompts.register(
                        prompt -> prompt.name("bootstrap-prompt"), List.of(PromptMessage.user("bootstrap"))))
                .withCompletions(completions -> completions.registerForPrompt(
                        "bootstrap-prompt", (ctx, request) -> CompletionResult.of(List.of("bootstrap"))))
                .build()) {
            assertThat(server.tools().find("bootstrap-tool")).isPresent();
            assertThat(server.resources().find("bootstrap-resource")).isPresent();
            assertThat(server.prompts().find("bootstrap-prompt")).isPresent();
            assertThat(server.completions().unregisterForPrompt("bootstrap-prompt"))
                    .isTrue();
        }
    }

    @Test
    void retainsCompletionRegistrationForPlainResource() {
        CompletionFn handler = (ctx, request) -> CompletionResult.of(List.of("sync-completion"));
        try (var server = TachyonServer.builder().build()) {
            server.resources()
                    .register(
                            resource -> resource.name("sync-resource").uri("test://sync-completed"),
                            (ctx, request) -> TextResourceContents.of(request.uri(), "text", "text/plain"));
            server.completions().registerForResource("test://sync-completed", handler);

            assertThat(server.completions().unregisterForResource("test://sync-completed"))
                    .isTrue();
        }
    }

    @Test
    void retainsAsyncCompletionRegistrations() {
        AsyncCompletionFn promptHandler =
                (ctx, request) -> CompletableFuture.completedFuture(CompletionResult.of(List.of("prompt-completion")));
        AsyncCompletionFn resourceHandler = (ctx, request) ->
                CompletableFuture.completedFuture(CompletionResult.of(List.of("resource-completion")));
        try (var server = TachyonServer.builder().build()) {
            server.prompts()
                    .register(prompt -> prompt.name("async-completed-prompt"), List.of(PromptMessage.user("prompt")));
            server.resources()
                    .register(
                            resource -> resource.name("async-resource").uri("test://async-completed"),
                            (ctx, request) -> TextResourceContents.of(request.uri(), "text", "text/plain"));
            server.completions()
                    .registerForPromptAsync("async-completed-prompt", promptHandler)
                    .registerForResourceAsync("test://async-completed", resourceHandler);

            assertThat(server.completions().unregisterForPrompt("async-completed-prompt"))
                    .isTrue();
            assertThat(server.completions().unregisterForResource("test://async-completed"))
                    .isTrue();
        }
    }

    @Test
    void builderHasNoFeatureRegistrationMethods() {
        assertThat(ServerBuilder.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain(
                        "tool",
                        "asyncTool",
                        "resource",
                        "asyncResource",
                        "resourceTemplate",
                        "asyncResourceTemplate",
                        "prompt",
                        "asyncPrompt",
                        "promptCompletion",
                        "asyncPromptCompletion",
                        "resourceCompletion",
                        "asyncResourceCompletion");
    }

    @Test
    void builderHasNoStartMethod() {
        assertThat(ServerBuilder.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("start");
    }

    @Test
    void portThrowsBeforeStart() {
        try (var server = TachyonServer.builder().build()) {
            assertThatIllegalStateException().isThrownBy(server::port);
        }
    }

    @Test
    void requiredTaskSupportRequiresTaskConnector() {
        assertThatThrownBy(() -> TachyonServer.builder()
                        .withTools(tools -> tools.register(
                                builder -> builder.name("task-tool").taskSupport(TaskSupport.REQUIRED),
                                (context, request) -> ToolResult.empty()))
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Task-producing tools require a TaskConnector");
    }

    @ParameterizedTest
    @EnumSource(
            value = TaskSupport.class,
            names = {"FORBIDDEN", "OPTIONAL"})
    void nonRequiredTaskSupportBuildsWithoutTaskConnector(TaskSupport taskSupport) {
        try (var server = TachyonServer.builder()
                .withTools(tools -> tools.register(
                        builder -> builder.name("task-tool").taskSupport(taskSupport),
                        (context, request) -> ToolResult.empty()))
                .build()) {
            assertThat(server).isNotNull();
        }
    }

    @Test
    void closesServerWhenBootstrapRegistrationThrowsSoResourcesDontLeak() {
        var eventStore = new TrackingSessionEventStore();
        var failure = new RuntimeException("boom");

        assertThatThrownBy(() -> TachyonServer.builder()
                        .session(s -> s.enabled().sessionEventStore(eventStore))
                        .withTools(tools -> {
                            throw failure;
                        })
                        .build())
                .isSameAs(failure);

        assertThat(eventStore.closed).isTrue();
    }

    @Test
    void closesServerWhenAnnotationRegistrationThrowsSoResourcesDontLeak() {
        var eventStore = new TrackingSessionEventStore();
        var failure = new RuntimeException("boom");

        assertThatThrownBy(() -> TachyonServer.builder()
                        .session(s -> s.enabled().sessionEventStore(eventStore))
                        .annotations(a -> a.withProvider((instance, context) -> {
                                    throw failure;
                                })
                                .register(new Object()))
                        .build())
                .isSameAs(failure);

        assertThat(eventStore.closed).isTrue();
    }

    @Test
    void publishesTheSameDefaultSessionStoresTheServerActuallyUses() {
        try (DefaultTachyonServer server = (DefaultTachyonServer)
                TachyonServer.builder().session(s -> s.enabled()).build()) {
            var store = server.config().session().sessionStore();
            var eventStore = server.config().session().sessionEventStore();
            assertThat(store).isNotNull();
            assertThat(eventStore).isNotNull();

            server.createSession("session-1");

            assertThat(store.find("session-1"))
                    .as("config() must expose the stores the server writes to, not a second set")
                    .isPresent();
            server.appendEvent(new SessionEvent.ResponseEvent("session-1", RequestId.of(1), "{}", 1L, -1, null));
            assertThat(eventStore.replay("session-1", -1)).hasSize(1);
        }
    }

    @Test
    void statelessIsTheDefaultAndCanBeStatedExplicitly() {
        assertThat(TachyonServer.builder().buildConfig().session()).isSameAs(SessionConfig.STATELESS);
        assertThat(TachyonServer.builder().stateless().buildConfig().session()).isSameAs(SessionConfig.STATELESS);
    }

    @Test
    void enabledAloneMakesTheServerStatefulWithDefaults() {
        var config =
                TachyonServer.builder().session(s -> s.enabled()).buildConfig().session();

        assertThat(config.enabled()).isTrue();
        assertThat(config.sessionTtl()).isEqualTo(SessionConfig.DEFAULT_SESSION_TTL);
        assertThat(config.janitorInterval()).isEqualTo(SessionConfig.DEFAULT_JANITOR_INTERVAL);
        assertThat(config.sessionStore()).isInstanceOf(InMemorySessionStore.class);
    }

    @Test
    void sessionOptionAloneMakesTheServerStateful() {
        var config = TachyonServer.builder()
                .session(s -> s.sessionTtl(Duration.ofMinutes(5)))
                .buildConfig()
                .session();

        assertThat(config.enabled()).isTrue();
        assertThat(config.sessionTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void statelessWithASessionOptionFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TachyonServer.builder()
                        .session(s -> s.sessionTtl(Duration.ofMinutes(5)))
                        .stateless()
                        .buildConfig())
                .withMessage(SessionConfig.SESSION_OPTIONS_REQUIRE_ENABLED);
    }

    @Test
    void statelessServerKeepsNoSessionState() {
        try (DefaultTachyonServer server =
                (DefaultTachyonServer) TachyonServer.builder().build()) {
            assertThat(server.isStateless()).isTrue();
            assertThat(server.config().session().sessionStore()).isNull();
            assertThat(server.config().session().sessionEventStore()).isNull();

            var session = server.createSession("ghost");
            session.activate();

            assertThat(server.getLocalSession("ghost"))
                    .as("the process-local runtime survives: a no-op store must not look like lost ownership")
                    .isPresent();
            assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
            server.appendEvent(new SessionEvent.ResponseEvent("ghost", RequestId.of(1), "{}", 1L, -1, null));
            assertThat(server.replay("ghost", -1)).isEmpty();
        }
    }

    private static final class TrackingSessionEventStore implements SessionEventStore {
        private boolean closed;

        @Override
        public void append(SessionEvent event) {}

        @Override
        public long drain(String sessionId, long cursor, Predicate<SessionEvent> processor) {
            return cursor;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void rejectsDuplicateExtensionIds() {
        var extension1 = new TestExtension("duplicate");
        var extension2 = new TestExtension("duplicate");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> TachyonServer.builder()
                        .withExtensions(extension1, extension2)
                        .build())
                .withMessageContaining("Duplicate extension ID: duplicate");
    }

    private static class TestExtension implements ServerExtension {
        private final String id;

        TestExtension(String id) {
            this.id = id;
        }

        @Override
        public String extensionId() {
            return id;
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.ALWAYS;
        }
    }
}
