/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandle;
import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandleAsync;
import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static dev.tachyonmcp.core.test.VirtualThreads.runInVirtualThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.domain.UriTemplateValue;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceFn;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.resources.Resources;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmptyResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListResourceTemplatesResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListResourcesResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ReadResourceResult;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.config.ResourcesConfig;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionRegistry;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultResourceRegistryTest {

    private static final ResourceFn EMPTY_HANDLER =
            (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain");

    private final ServerEngine server = newEngine(b -> {});
    private final DefaultResourceRegistry registry =
            new DefaultResourceRegistry(server, ResourcesConfig.builder().build());
    private final HashMap<String, RpcMethodHandler<?, ?>> handlers = new HashMap<>();

    private static ResourceDescriptor resource(String name) {
        return ResourceDescriptor.of(name, "test://" + name, null, null);
    }

    private static String scalar(Map<String, UriTemplateValue> params, String name) {
        return params.get(name).scalarValue();
    }

    private static DispatchContext context(Session session, ServerEngine server) {
        var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
        ctx.setSession(session);
        return ctx;
    }

    @BeforeEach
    void setUp() {
        ResourceMethodHandlers.register(handlers, registry, new SubscriptionRegistry(server));
    }

    @Test
    void shouldReturnEmptyListWhenNoResourcesRegistered() throws Exception {
        var result = decodeAndHandle(handlers.get("resources/list"), DefaultDispatchContext.stateless(server), null);

        assertThat(result).isInstanceOf(ListResourcesResult.class);
        assertThat(((ListResourcesResult) result).resources()).isEmpty();
    }

    @Test
    void listWithZeroLimitUsesDefaultPageSize() {
        registry.register(resource("r1"), EMPTY_HANDLER);
        registry.register(resource("r2"), EMPTY_HANDLER);
        var result = registry.list(0, null);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void listWithCursorSkipsPastCursor() {
        registry.register(resource("alpha"), EMPTY_HANDLER);
        registry.register(resource("beta"), EMPTY_HANDLER);
        registry.register(resource("gamma"), EMPTY_HANDLER);
        var firstPage = registry.list(1, null);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotNull();

        var result = registry.list(1, firstPage.nextCursor());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().name()).isEqualTo("beta");
    }

    @Test
    void listReturnsCursorWhenMoreItemsAvailable() {
        registry.register(resource("a"), EMPTY_HANDLER);
        registry.register(resource("b"), EMPTY_HANDLER);
        var result = registry.list(1, null);
        assertThat(result.nextCursor()).isNotNull();
    }

    @Test
    void listReturnsNullCursorWhenAllItemsReturned() {
        registry.register(resource("a"), EMPTY_HANDLER);
        var result = registry.list(10, null);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void listWithCustomPageSize() {
        var reg = new DefaultResourceRegistry(
                server, ResourcesConfig.builder().pageSize(1).build());
        reg.register(resource("a"), EMPTY_HANDLER);
        reg.register(resource("b"), EMPTY_HANDLER);
        var result = reg.list(0, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.nextCursor()).isNotNull();
    }

    @Test
    void registerIsNoOpWhenResourcesCapabilityIsOff() {
        var reg = new DefaultResourceRegistry(
                server, ResourcesConfig.builder().off().build());
        var changeCount = new AtomicInteger();
        reg.onChange(changeCount::incrementAndGet);

        reg.register(resource("a"), EMPTY_HANDLER);
        reg.registerTemplate(
                ResourceTemplateDescriptor.builder()
                        .name("template-entry")
                        .uriTemplate("test://entry/{id}")
                        .build(),
                (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain"));

        assertThat(reg.find("a")).isEmpty();
        assertThat(reg.descriptors()).isEmpty();
        assertThat(reg.findTemplate("template-entry")).isEmpty();
        assertThat(reg.templateDescriptors()).isEmpty();
        assertThat(changeCount).hasValue(0);
    }

    @Test
    void interfaceDefaultBuilderOverloadsRegisterResourcesAndTemplates() {
        Resources api = registry;

        api.register(resource -> resource.name("sync").uri("test://sync"), EMPTY_HANDLER)
                .registerAsync(
                        resource -> resource.name("async").uri("test://async"),
                        (ctx, request) -> CompletableFuture.completedFuture(
                                TextResourceContents.of(request.uri(), "async", "text/plain")))
                .registerTemplate(
                        template -> template.name("sync-template").uriTemplate("test://sync/{id}"),
                        (ctx, request) ->
                                TextResourceContents.of(request.uri(), scalar(request.params(), "id"), "text/plain"))
                .registerTemplateAsync(
                        template -> template.name("async-template").uriTemplate("test://async/{id}"),
                        (ctx, request) -> CompletableFuture.completedFuture(
                                TextResourceContents.of(request.uri(), scalar(request.params(), "id"), "text/plain")));

        assertThat(api.descriptors()).extracting(ResourceDescriptor::name).containsExactly("async", "sync");
        assertThat(api.find("sync")).isPresent();
        assertThat(api.find("missing")).isEmpty();
        assertThat(api.templateDescriptors())
                .extracting(ResourceTemplateDescriptor::name)
                .containsExactly("async-template", "sync-template");
        assertThat(api.findTemplate("sync-template")).isPresent();
        assertThat(api.findTemplate("missing")).isEmpty();
        assertThat(api.unregister("sync")).isTrue();
        assertThat(api.unregister("sync")).isFalse();
        assertThat(api.unregisterTemplate("sync-template")).isTrue();
        assertThat(api.unregisterTemplate("sync-template")).isFalse();
    }

    @Test
    void findByUriReturnsDescriptorForRegisteredUri() {
        registry.register(resource("r1"), EMPTY_HANDLER);

        var found = registry.findByUri("test://r1");

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("r1");
    }

    @Test
    void findByUriReturnsEmptyForUnknownUri() {
        registry.register(resource("r1"), EMPTY_HANDLER);

        assertThat(registry.findByUri("test://unknown")).isEmpty();
    }

    @Test
    void unregisterByUriRemovesResourceAndFiresOnChange() {
        registry.register(resource("r1"), EMPTY_HANDLER);
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        var removed = registry.unregisterByUri("test://r1");

        assertThat(removed).isTrue();
        assertThat(registry.find("r1")).isEmpty();
        assertThat(registry.findByUri("test://r1")).isEmpty();
        assertThat(registry.descriptors()).isEmpty();
        assertThat(callCount).hasValue(1);
    }

    @Test
    void unregisterByUriReturnsFalseForNonExistentUri() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        var removed = registry.unregisterByUri("test://nonexistent");

        assertThat(removed).isFalse();
        assertThat(callCount).hasValue(0);
    }

    @Test
    void unregisterByUriMakesHandlerUnresolvable() throws Exception {
        registry.register(
                ResourceDescriptor.of("r1", "test://r1", null, "text/plain"),
                (ctx, request) -> TextResourceContents.of(request.uri(), "content", "text/plain"));

        registry.unregisterByUri("test://r1");

        var result = decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "test://r1"));
        assertThat(result).isInstanceOf(ServerError.class);
        assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.RESOURCE_NOT_FOUND);
    }

    @Test
    void unregisterRemovesSubscriptionsForResourceUri() {
        registry.register(resource("r1"), EMPTY_HANDLER);
        registry.subscribe("test://r1", "s1");

        assertThat(registry.unregister("r1")).isTrue();

        assertThat(registry.subscriptions).doesNotContainKey("test://r1");
    }

    @Test
    void unregisterByUriRemovesSubscriptionsForUri() {
        registry.register(resource("r1"), EMPTY_HANDLER);
        registry.subscribe("test://r1", "s1");

        assertThat(registry.unregisterByUri("test://r1")).isTrue();

        assertThat(registry.subscriptions).doesNotContainKey("test://r1");
    }

    @Test
    void shouldReturnErrorWhenResourceNotFound() throws Exception {
        var result = decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "test://nonexistent"));

        assertThat(result).isInstanceOf(ServerError.class);
        assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.RESOURCE_NOT_FOUND);
    }

    @Test
    void shouldReturnErrorWhenUriMissing() {
        assertThatThrownBy(() -> decodeAndHandle(
                        handlers.get("resources/read"), DefaultDispatchContext.stateless(server), Map.of()))
                .isInstanceOf(RequestMappingException.class)
                .extracting(e -> ((RequestMappingException) e).error().kind())
                .isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "test://bad path", "test://bad%2", "test://bad\npath"})
    void shouldRejectInvalidReadResourceUri(String uri) throws Exception {
        var result = decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", uri));

        assertThat(result).isEqualTo(new ServerError(ServerError.Kind.INVALID_PARAMS, "Invalid resource URI"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"resources/subscribe", "resources/unsubscribe"})
    void shouldRejectInvalidSubscriptionUri(String method) throws Exception {
        var result = decodeAndHandle(
                handlers.get(method), DefaultDispatchContext.stateless(server), Map.of("uri", "test://bad uri"));

        assertThat(result).isEqualTo(new ServerError(ServerError.Kind.INVALID_PARAMS, "Invalid resource URI"));
    }

    @Test
    void shouldRejectOversizedResourceUri() throws Exception {
        var result = decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "test://" + "a".repeat(8_192)));

        assertThat(result).isEqualTo(new ServerError(ServerError.Kind.INVALID_PARAMS, "Invalid resource URI"));
    }

    @Test
    void shouldMapInvalidArgumentExceptionToInvalidParams() throws Exception {
        registry.register(
                ResourceDescriptor.of("bad-input", "test://bad-input", null, "text/plain"), (ctx, request) -> {
                    throw new InvalidArgumentException("city", "unknown city");
                });

        var result = runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "test://bad-input")));

        assertThat(result).isInstanceOf(ServerError.class);
        assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void shouldReadResourceContentByUri() throws Exception {
        var descriptor = ResourceDescriptor.of("test-resource", "test://resource/1", "Test resource", "text/plain");
        registry.register(
                descriptor, (ctx, request) -> TextResourceContents.of("test://resource/1", "content", "text/plain"));

        var result = runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "test://resource/1")));

        assertThat(result).isInstanceOf(ReadResourceResult.class);
        var readResult = (ReadResourceResult) result;
        assertThat(readResult.contents()).hasSize(1);
        assertThat(readResult.contents().getFirst().uri()).isEqualTo("test://resource/1");
    }

    @Test
    void shouldPassStaticResourceDetailsToCommonHandler() throws Exception {
        var capturedUri = new AtomicReference<@Nullable String>();
        var capturedParams = new AtomicReference<@Nullable Map<String, UriTemplateValue>>();
        var capturedTemplate = new AtomicReference<@Nullable String>();
        registry.register(resource("static-request"), (ctx, request) -> {
            capturedUri.set(request.uri());
            capturedParams.set(request.params());
            capturedTemplate.set(request.uriTemplate());
            return TextResourceContents.of(request.uri(), "static", "text/plain");
        });

        runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.of("uri", "test://static-request")));

        assertThat(capturedUri).hasValue("test://static-request");
        assertThat(capturedParams.get()).isEmpty();
        assertThat(capturedTemplate).hasNullValue();
    }

    @Test
    void shouldPassTemplateResourceDetailsToCommonHandler() throws Exception {
        var capturedUri = new AtomicReference<@Nullable String>();
        var capturedParams = new AtomicReference<@Nullable Map<String, UriTemplateValue>>();
        var capturedTemplate = new AtomicReference<@Nullable String>();
        registry.registerTemplate(
                ResourceTemplateDescriptor.builder()
                        .name("template-request")
                        .uriTemplate("test://items/{id}")
                        .build(),
                (ctx, request) -> {
                    capturedUri.set(request.uri());
                    capturedParams.set(request.params());
                    capturedTemplate.set(request.uriTemplate());
                    return TextResourceContents.of(request.uri(), "template", "text/plain");
                });

        runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.of("uri", "test://items/42")));

        assertThat(capturedUri).hasValue("test://items/42");
        assertThat(capturedParams.get()).containsExactly(Map.entry("id", new UriTemplateValue.Scalar("42")));
        assertThat(capturedTemplate).hasValue("test://items/{id}");
    }

    @Test
    void shouldRunSynchronousResourceFnOnCallingVirtualThread() throws Exception {
        var handlerThread = new AtomicReference<@Nullable Thread>();
        registry.register(resource("sync-thread"), (ctx, request) -> {
            handlerThread.set(Thread.currentThread());
            return TextResourceContents.of(request.uri(), "sync", "text/plain");
        });

        runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.of("uri", "test://sync-thread")));

        assertThat(handlerThread.get()).isNotNull().matches(Thread::isVirtual);
    }

    @Test
    void shouldInvokeAsynchronousResourceFnOnVirtualThreadWithoutBlockingIt() throws Exception {
        var handlerThread = new AtomicReference<@Nullable Thread>();
        var callerThread = new AtomicReference<@Nullable Thread>();
        var contents = TextResourceContents.of("test://async-thread", "async", "text/plain");
        var completion = new CompletableFuture<TextResourceContents>();
        registry.registerAsync(resource("async-thread"), (ctx, request) -> {
            handlerThread.set(Thread.currentThread());
            return completion;
        });

        var stage = runInVirtualThread(() -> {
            callerThread.set(Thread.currentThread());
            return decodeAndHandleAsync(
                    handlers.get("resources/read"),
                    DefaultDispatchContext.stateless(server),
                    Map.of("uri", "test://async-thread"));
        });

        // handleAsync() returned before the handler's future completed: the calling
        // virtual thread was never blocked joining the result.
        assertThat(stage.toCompletableFuture()).isNotDone();
        assertThat(handlerThread.get()).isNotNull().matches(Thread::isVirtual);
        assertThat(handlerThread.get()).isSameAs(callerThread.get());

        completion.complete(contents);

        var result = (ReadResourceResult) stage.toCompletableFuture().get();
        assertThat(result.contents()).hasSize(1);
        assertThat(result.contents().getFirst().uri()).isEqualTo(contents.uri());
    }

    @Test
    void shouldReturnEmptyTemplateList() throws Exception {
        var result = decodeAndHandle(
                handlers.get("resources/templates/list"), DefaultDispatchContext.stateless(server), null);

        assertThat(result).isInstanceOf(ListResourceTemplatesResult.class);
        assertThat(((ListResourceTemplatesResult) result).resourceTemplates()).isEmpty();
    }

    @Test
    void subscribeRejectsNullSession() throws Exception {
        var result = decodeAndHandle(
                handlers.get("resources/subscribe"),
                DefaultDispatchContext.stateless(server),
                Map.of("uri", "test://resource/1"));

        assertThat(result).isInstanceOf(ServerError.class);
        assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INVALID_REQUEST);
    }

    @Test
    void unsubscribeRejectsNullSession() throws Exception {
        var result = decodeAndHandle(
                handlers.get("resources/unsubscribe"),
                DefaultDispatchContext.stateless(server),
                Map.of("uri", "test://resource/1"));

        assertThat(result).isInstanceOf(ServerError.class);
        assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INVALID_REQUEST);
    }

    @Test
    void shouldRecordSubscription() throws Exception {
        var session = server.createSession("test-session");
        session.activate();

        var result = decodeAndHandle(
                handlers.get("resources/subscribe"), context(session, server), Map.of("uri", "test://resource/1"));

        assertThat(result).isInstanceOf(EmptyResult.class);
        assertThat(registry.isSubscribed("test://resource/1", "test-session")).isTrue();
    }

    @Test
    void shouldRemoveSubscriptionOnUnsubscribe() throws Exception {
        var session = server.createSession("test-session");
        session.activate();

        decodeAndHandle(
                handlers.get("resources/subscribe"), context(session, server), Map.of("uri", "test://resource/1"));
        assertThat(registry.isSubscribed("test://resource/1", "test-session")).isTrue();

        var result = decodeAndHandle(
                handlers.get("resources/unsubscribe"), context(session, server), Map.of("uri", "test://resource/1"));

        assertThat(result).isInstanceOf(EmptyResult.class);
        assertThat(registry.isSubscribed("test://resource/1", "test-session")).isFalse();
    }

    @Test
    void shouldPruneMapEntryWhenLastSubscriberLeaves() {
        registry.subscribe("test://resource/1", "s1");
        registry.subscribe("test://resource/1", "s2");

        registry.unsubscribe("test://resource/1", "s1");
        assertThat(registry.subscriptions).containsKey("test://resource/1");

        registry.unsubscribe("test://resource/1", "s2");
        assertThat(registry.subscriptions).doesNotContainKey("test://resource/1");
    }

    @Test
    void concurrentSubscribeSurvivesUnsubscribePruning() throws Exception {
        // Race under test: unsubscribe empties the set and prunes the map entry while a
        // concurrent subscribe is adding to it. With add/remove outside the map operation the
        // subscribe could land in the pruned (stranded) set and be silently lost; compute-based
        // mutation serializes both on the map's per-key lock, so the subscription must survive.
        var uri = "test://resource/race";
        int iterations = 1_000;
        try (var exec = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < iterations; i++) {
                registry.subscribe(uri, "leaver");
                var start = new java.util.concurrent.CountDownLatch(1);
                var unsub = exec.submit(() -> {
                    start.await();
                    registry.unsubscribe(uri, "leaver");
                    return null;
                });
                var sub = exec.submit(() -> {
                    start.await();
                    registry.subscribe(uri, "joiner");
                    return null;
                });
                start.countDown();
                unsub.get();
                sub.get();

                assertThat(registry.isSubscribed(uri, "joiner"))
                        .as("iteration %d: concurrent subscribe lost to pruning", i)
                        .isTrue();
                registry.unsubscribe(uri, "joiner");
            }
        }
    }

    @Test
    void notifyResourceUpdatedDropsDeadSessionSubscriptions() {
        var live = server.createSession("live-session");
        live.connection(new CollectingConnection());
        live.activate();

        registry.subscribe("test://resource/1", "live-session");
        registry.subscribe("test://resource/1", "dead-session"); // no such session on the server

        registry.notifyResourceUpdated("test://resource/1");

        assertThat(registry.isSubscribed("test://resource/1", "live-session")).isTrue();
        assertThat(registry.isSubscribed("test://resource/1", "dead-session")).isFalse();
    }

    @Test
    void shouldSendUpdatedNotificationToSubscribedSession() throws Exception {
        var conn = new CollectingConnection();
        var sess = server.createSession("notify-test");
        sess.connection(conn);
        sess.activate();

        decodeAndHandle(handlers.get("resources/subscribe"), context(sess, server), Map.of("uri", "test://resource/1"));
        registry.register(
                ResourceDescriptor.of("test-resource", "test://resource/1", "Test resource", "text/plain"),
                (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain"));

        registry.notifyResourceUpdated("test://resource/1");

        var notificationEvent = conn.sent.stream()
                .filter(e -> e.data().contains("notifications/resources/updated"))
                .findFirst();
        assertThat(notificationEvent).isPresent();
    }

    @Test
    void shouldFireOnChangeWhenResourceAdded() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.register(
                ResourceDescriptor.of("r1", "test://r1", null, null),
                (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain"));

        assertThat(callCount).hasValue(1);
    }

    @Test
    void shouldFireOnChangeWhenExistingResourceRemoved() {
        registry.register(
                ResourceDescriptor.of("r1", "test://r1", null, null),
                (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain"));

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.unregister("r1");

        assertThat(callCount).hasValue(1);
    }

    @Test
    void shouldNotFireOnChangeWhenRegisteringIdenticalResource() {
        var descriptor = ResourceDescriptor.of("doc", "resource://doc", null, "text/plain");
        registry.register(descriptor, EMPTY_HANDLER);

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        // same descriptor + same handler instance is a no-op: no add, no change
        registry.register(descriptor, EMPTY_HANDLER);

        assertThat(callCount).hasValue(0);
        assertThat(registry.descriptors()).hasSize(1);
    }

    @Test
    void shouldNotFireOnChangeWhenRemovingNonExistentResource() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.unregister("does-not-exist");

        assertThat(callCount).hasValue(0);
    }

    @Test
    void shouldFireOnChangeWhenTemplateAdded() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.registerTemplate(
                ResourceTemplateDescriptor.builder()
                        .name("tmpl")
                        .uriTemplate("test://tmpl/{id}")
                        .build(),
                (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain"));

        assertThat(callCount).hasValue(1);
    }

    @Test
    void shouldReplaceHandlerAndFireOnChangeWhenAddedWithSameName() {
        registry.register(
                ResourceDescriptor.of("doc", "resource://doc-v1", null, "text/plain"),
                (ctx, request) -> TextResourceContents.of(request.uri(), "v1", "text/plain"));

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.register(
                ResourceDescriptor.of("doc", "resource://doc-v1", null, "text/plain"),
                (ctx, request) -> TextResourceContents.of(request.uri(), "v2", "text/plain"));

        assertThat(callCount).hasValue(1);
        assertThat(registry.find("doc")).isPresent();
        assertThat(registry.descriptors()).hasSize(1);
    }

    @Test
    void sameNameAtNewUriCoexistsWithoutEvictingTheOld() throws Exception {
        // URI is identity: two resources may share a name (e.g. same-named skills mounted under
        // different namespace prefixes) without one silently evicting the other.
        registry.register(
                ResourceDescriptor.of("doc", "resource://doc-v1", null, "text/plain"),
                (ctx, request) -> TextResourceContents.of(request.uri(), "v1", "text/plain"));

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.register(
                ResourceDescriptor.of("doc", "resource://doc-v2", null, "text/plain"),
                (ctx, request) -> TextResourceContents.of(request.uri(), "v2", "text/plain"));

        assertThat(callCount).hasValue(1);

        // both URIs remain independently resolvable
        var v1Result = runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "resource://doc-v1")));
        assertThat(v1Result).isInstanceOf(ReadResourceResult.class);

        var v2Result = runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "resource://doc-v2")));
        assertThat(v2Result).isInstanceOf(ReadResourceResult.class);
        assertThat(registry.descriptors())
                .extracting(ResourceDescriptor::uri)
                .containsExactlyInAnyOrder("resource://doc-v1", "resource://doc-v2");
    }

    @Test
    void registeringSameNameAtNewUriKeepsSubscriptionsForBothUris() {
        registry.register(ResourceDescriptor.of("doc", "resource://doc-v1", null, "text/plain"), EMPTY_HANDLER);
        registry.subscribe("resource://doc-v1", "s1");
        registry.subscribe("resource://doc-v2", "s2");

        registry.register(ResourceDescriptor.of("doc", "resource://doc-v2", null, "text/plain"), EMPTY_HANDLER);

        assertThat(registry.isSubscribed("resource://doc-v1", "s1")).isTrue();
        assertThat(registry.isSubscribed("resource://doc-v2", "s2")).isTrue();
    }

    @Test
    void shouldMapAllResourceDescriptorFieldsToProtocolModel() throws Exception {
        var annotations = Annotations.of(List.of(Role.USER), 0.9, "2026-01-01T00:00:00Z");
        var icon = Icon.of("https://example.com/icon.png", "image/png", List.of(), null);
        var descriptor = ResourceDescriptor.of(
                "full-resource",
                "test://full",
                "Full description",
                "text/plain",
                "Full Title",
                annotations,
                1024L,
                List.of(icon));
        registry.register(
                descriptor, (ctx, request) -> TextResourceContents.of(request.uri(), "content", "text/plain"));

        var result = (ListResourcesResult)
                decodeAndHandle(handlers.get("resources/list"), DefaultDispatchContext.stateless(server), null);

        assertThat(result.resources()).hasSize(1);
        var resource = result.resources().getFirst();
        assertThat(resource.name()).isEqualTo("full-resource");
        assertThat(resource.uri()).isEqualTo("test://full");
        assertThat(resource.description()).isEqualTo("Full description");
        assertThat(resource.mimeType()).isEqualTo("text/plain");
        assertThat(resource.title()).isEqualTo("Full Title");
        assertThat(resource.annotations()).isNotNull();
        assertThat(resource.annotations().audience())
                .containsExactly(dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role.USER);
        assertThat(resource.annotations().priority()).isEqualTo(annotations.priority());
        assertThat(resource.annotations().lastModified()).isEqualTo(annotations.lastModified());
        assertThat(resource.size()).isEqualTo(1024L);
        assertThat(resource.icons()).hasSize(1);
        assertThat(resource.icons().getFirst().src()).isEqualTo("https://example.com/icon.png");
    }

    @Test
    void shouldRejectTemplateWithBlankName() {
        assertThatThrownBy(() -> ResourceTemplateDescriptor.builder()
                        .name("")
                        .uriTemplate("resource://{id}")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectTemplateWithBlankUriTemplate() {
        assertThatThrownBy(() -> ResourceTemplateDescriptor.builder()
                        .name("tmpl")
                        .uriTemplate("  ")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uriTemplate");
    }

    @Test
    void shouldRejectTemplateWithInvalidVariableName() {
        assertThatThrownBy(() -> ResourceTemplateDescriptor.builder()
                        .name("bad")
                        .uriTemplate("resource://{foo-bar}")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foo-bar");
    }

    @Test
    void shouldRejectTemplateWithEmptyBraces() {
        assertThatThrownBy(() -> ResourceTemplateDescriptor.builder()
                        .name("bad")
                        .uriTemplate("resource://{}")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Empty URI template expression");
    }

    @Test
    void shouldRejectTemplateWithUnmatchedOpenBrace() {
        assertThatThrownBy(() -> ResourceTemplateDescriptor.builder()
                        .name("bad")
                        .uriTemplate("resource://{foo")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed URI template");
    }

    @Test
    void shouldAllowRepeatedVariableNames() {
        var descriptor = ResourceTemplateDescriptor.builder()
                .name("dictionary")
                .uriTemplate("resource://dictionary/{term:1}/{term}")
                .build();

        assertThat(descriptor.uriTemplate()).isEqualTo("resource://dictionary/{term:1}/{term}");
    }

    @Test
    void shouldPassExplodedSequenceToTemplateHandler() throws Exception {
        var captured = new AtomicReference<@Nullable Map<String, UriTemplateValue>>();
        registry.registerTemplate(
                ResourceTemplateDescriptor.builder()
                        .name("files")
                        .uriTemplate("resource://files{/segments*}")
                        .build(),
                (ctx, request) -> {
                    captured.set(request.params());
                    return TextResourceContents.of(request.uri(), "ok", "text/plain");
                });

        var result = runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "resource://files/one/two%20words")));

        assertThat(result).isInstanceOf(ReadResourceResult.class);
        assertThat(captured.get())
                .containsExactly(Map.entry("segments", new UriTemplateValue.Sequence(List.of("one", "two words"))));
    }

    @Test
    void shouldPreferMoreSpecificTemplateOnOverlap() throws Exception {
        var matched = new AtomicReference<@Nullable String>();
        registry.registerTemplate(
                ResourceTemplateDescriptor.builder()
                        .name("generic")
                        .uriTemplate("resource://{type}/{id}")
                        .build(),
                (ctx, request) -> {
                    matched.set("generic");
                    return TextResourceContents.of(request.uri(), "generic", "text/plain");
                });
        registry.registerTemplate(
                ResourceTemplateDescriptor.builder()
                        .name("specific")
                        .uriTemplate("resource://users/{id}")
                        .build(),
                (ctx, request) -> {
                    matched.set("specific");
                    return TextResourceContents.of(request.uri(), "specific", "text/plain");
                });

        runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.<String, Object>of("uri", "resource://users/42")));

        assertThat(matched).hasValue("specific");
    }

    @Test
    void shouldMapAllResourceTemplateFieldsToProtocolModel() throws Exception {
        var annotations = Annotations.of(List.of(), 0.5, null);
        var icon = Icon.of("https://example.com/tmpl.png", null, List.of(), null);
        registry.registerTemplate(
                ResourceTemplateDescriptor.builder()
                        .name("tmpl")
                        .uriTemplate("test://tmpl/{id}")
                        .description("Template desc")
                        .mimeType("text/plain")
                        .title("Template Title")
                        .annotations(annotations)
                        .icons(icon)
                        .build(),
                (ctx, request) -> TextResourceContents.of(
                        request.uri(), "content-" + scalar(request.params(), "id"), "text/plain"));

        var result = (ListResourceTemplatesResult) decodeAndHandle(
                handlers.get("resources/templates/list"), DefaultDispatchContext.stateless(server), null);

        assertThat(result.resourceTemplates()).hasSize(1);
        var tmpl = result.resourceTemplates().getFirst();
        assertThat(tmpl.name()).isEqualTo("tmpl");
        assertThat(tmpl.uriTemplate()).isEqualTo("test://tmpl/{id}");
        assertThat(tmpl.description()).isEqualTo("Template desc");
        assertThat(tmpl.mimeType()).isEqualTo("text/plain");
        assertThat(tmpl.title()).isEqualTo("Template Title");
        assertThat(tmpl.annotations()).isNotNull();
        assertThat(tmpl.annotations().priority()).isEqualTo(annotations.priority());
        assertThat(tmpl.icons()).hasSize(1);
        assertThat(tmpl.icons().getFirst().src()).isEqualTo("https://example.com/tmpl.png");
    }

    @Test
    void shouldReportResourceSizeWithoutLoadingContent() throws Exception {
        // size is declared upfront; content is loaded lazily via handler only on resources/read
        var descriptor = ResourceDescriptor.of(
                "sized-resource",
                "test://sized",
                "A resource",
                "application/octet-stream",
                null,
                null,
                4096L,
                List.of());
        var contentLoaded = new java.util.concurrent.atomic.AtomicBoolean(false);
        registry.register(descriptor, (ctx, request) -> {
            contentLoaded.set(true);
            return TextResourceContents.of("test://sized", "data", "application/octet-stream");
        });

        // resources/list returns size WITHOUT loading content
        var listResult = (ListResourcesResult)
                decodeAndHandle(handlers.get("resources/list"), DefaultDispatchContext.stateless(server), null);
        assertThat(listResult.resources().getFirst().size()).isEqualTo(4096L);
        assertThat(contentLoaded).isFalse();

        // resources/read triggers lazy content load
        runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                Map.of("uri", "test://sized")));
        assertThat(contentLoaded).isTrue();
    }

    @Test
    void registerRejectsUriAlreadyOwnedByDifferentName() {
        registry.register(ResourceDescriptor.of("first", "resource://shared", null, "text/plain"), EMPTY_HANDLER);

        assertThatThrownBy(() -> registry.register(
                        ResourceDescriptor.of("second", "resource://shared", null, "text/plain"), EMPTY_HANDLER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource://shared")
                .hasMessageContaining("first");

        // rejected registration must not leak into either index
        assertThat(registry.find("second")).isEmpty();
        assertThat(registry.descriptors()).extracting(ResourceDescriptor::name).containsExactly("first");
        assertThat(registry.getByUri("resource://shared").descriptor().name()).isEqualTo("first");
    }

    @Test
    void registerAllowsSameNameToKeepItsOwnUri() {
        registry.register(ResourceDescriptor.of("doc", "resource://doc", null, "text/plain"), EMPTY_HANDLER);
        // same name re-registering its own URI is a replace, not a conflict
        registry.register(ResourceDescriptor.of("doc", "resource://doc", "updated", "text/plain"), EMPTY_HANDLER);

        assertThat(registry.find("doc")).map(ResourceDescriptor::description).hasValue("updated");
        assertThat(registry.descriptors()).hasSize(1);
    }

    @Test
    void concurrentRegisterOfDifferentUrisWithSameNameBothSurvive() throws Exception {
        // Two independent resources sharing a name, registered concurrently: URI is identity, so
        // both must land in the index with no lost update, regardless of interleaving.
        var name = "A";
        var u1 = "resource://a/1";
        var u2 = "resource://a/2";
        int iterations = 1_000;
        try (var exec = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < iterations; i++) {
                var start = new java.util.concurrent.CountDownLatch(1);
                var r1 = exec.submit(() -> {
                    start.await();
                    registry.register(ResourceDescriptor.of(name, u1, null, null), EMPTY_HANDLER);
                    return null;
                });
                var r2 = exec.submit(() -> {
                    start.await();
                    registry.register(ResourceDescriptor.of(name, u2, null, null), EMPTY_HANDLER);
                    return null;
                });
                start.countDown();
                r1.get();
                r2.get();

                assertThat(registry.getByUri(u1))
                        .as("iteration %d: first URI must resolve", i)
                        .isNotNull();
                assertThat(registry.getByUri(u2))
                        .as("iteration %d: second URI must resolve", i)
                        .isNotNull();
            }
        }
    }

    private static class CollectingConnection implements SseConnection {
        final java.util.ArrayList<SseEvent> sent = new java.util.ArrayList<>();
        final boolean writable = true;

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public void send(SseEvent event) {
            sent.add(event);
        }
    }
}
