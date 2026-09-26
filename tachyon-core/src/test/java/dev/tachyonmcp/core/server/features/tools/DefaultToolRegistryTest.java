/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tools;

import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandle;
import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandleAsync;
import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static dev.tachyonmcp.core.test.TestUtils.parseJson;
import static dev.tachyonmcp.core.test.TestUtils.properties;
import static dev.tachyonmcp.core.test.VirtualThreads.runInVirtualThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.PayloadSerde;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.AbstractToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListToolsResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.config.FeatureConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.Jackson3JsonFactory;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import dev.tachyonmcp.core.server.json.NetworkntJsonSchemaValidator;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class DefaultToolRegistryTest {

    // language=json
    private static final JsonNode TEST_SCHEMA = parseJson("""
        {"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}
        """);

    private static final PayloadSerde TEST_SERDE = new JacksonPayloadSerde();

    private final DefaultToolRegistry registry = new DefaultToolRegistry(
            Jackson3JsonFactory.INSTANCE, FeatureConfig.builder().build());

    private static void registerHandlers(DefaultToolRegistry registry, Map<String, RpcMethodHandler<?, ?>> handlers) {
        var validator = new NetworkntJsonSchemaValidator();
        ToolMethodHandlers.register(handlers, registry, validator, validator, TEST_SERDE, TEST_SERDE);
    }

    @Test
    void listToolsReturnsEmptyListWhenNoToolsRegistered() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        var listHandler = handlers.get("tools/list");
        var result = decodeAndHandle(listHandler, DefaultDispatchContext.noop(), null);
        assertThat(result).isInstanceOf(ListToolsResult.class);
        assertThat(((ListToolsResult) result).tools()).isEmpty();
    }

    @Test
    void listTools() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);

        // minimal: only name set; all optional fields absent
        registry.register(testTool("minimal-tool", null, null));

        // full: all possible fields set
        // language=json
        var outputSchema = parseJson("""
            {"type":"object","properties":{"result":{"type":"string"}}}
            """);
        var annotations = ToolAnnotations.of(null, true, false, true, false);
        registry.register(
                ToolDescriptor.builder()
                        .name("full-tool")
                        .title("Full Tool")
                        .description("Does everything")
                        .inputSchema(TEST_SCHEMA.toString())
                        .outputSchema(outputSchema.toString())
                        .taskSupport(TaskSupport.OPTIONAL)
                        .annotations(annotations)
                        .build(),
                (context, request) -> ToolResult.text("ok"));

        var listResult =
                (ListToolsResult) decodeAndHandle(handlers.get("tools/list"), DefaultDispatchContext.noop(), null);
        assertThat(listResult.tools()).hasSize(2);

        var minimal = listResult.tools().stream()
                .filter(t -> "minimal-tool".equals(t.name()))
                .findFirst()
                .orElseThrow();
        assertThat(minimal.title()).isNull();
        assertThat(minimal.description()).isNull();
        assertThat(parseJson(minimal.inputSchema()))
                .isEqualTo(parseJson(JsonSchema.objectSchema().json()));
        assertThat(minimal.outputSchema()).isNull();
        assertThat(minimal.execution()).isNull();

        var full = listResult.tools().stream()
                .filter(t -> "full-tool".equals(t.name()))
                .findFirst()
                .orElseThrow();
        assertThat(full.title()).isEqualTo("Full Tool");
        assertThat(full.description()).isEqualTo("Does everything");
        assertThat(parseJson(full.inputSchema())).isEqualTo(TEST_SCHEMA);
        assertThat(full.outputSchema()).isNotNull();
        assertThat(parseJson(full.outputSchema())).isEqualTo(outputSchema);
        assertThat(full.execution()).isNotNull();
        assertThat(full.execution().taskSupport()).isEqualTo("optional");
        assertThat(full.annotations()).isNotNull();
        assertThat(full.annotations().readOnlyHint()).isEqualTo(annotations.readOnlyHint());
        assertThat(full.annotations().destructiveHint()).isEqualTo(annotations.destructiveHint());
        assertThat(full.annotations().idempotentHint()).isEqualTo(annotations.idempotentHint());
        assertThat(full.annotations().openWorldHint()).isEqualTo(annotations.openWorldHint());
    }

    @Test
    void callToolNotFound() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);

        var callHandler = handlers.get("tools/call");
        var params = Map.<String, Object>of("name", "nonexistent");

        var result = decodeAndHandle(callHandler, DefaultDispatchContext.noop(), params);
        assertThat(result).isInstanceOf(ServerError.class);
        var err = (ServerError) result;
        assertThat(err.kind()).isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void callToolMissingName() {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);

        var callHandler = handlers.get("tools/call");

        assertThatThrownBy(() -> decodeAndHandle(callHandler, DefaultDispatchContext.noop(), Map.of()))
                .isInstanceOf(RequestMappingException.class)
                .extracting(e -> ((RequestMappingException) e).error().kind())
                .isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void callToolWithNullParams() {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);

        var callHandler = handlers.get("tools/call");

        assertThatThrownBy(() -> decodeAndHandle(callHandler, DefaultDispatchContext.noop(), null))
                .isInstanceOf(RequestMappingException.class)
                .extracting(e -> ((RequestMappingException) e).error().kind())
                .isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void callToolReturnsResult() throws Exception {
        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("test");
            session.activate();
            var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
            registerHandlers(registry, handlers);
            registry.register(testTool("echo", "Echo", TEST_SCHEMA));

            var callHandler = handlers.get("tools/call");
            var params = Map.of("name", "echo", "arguments", Map.of("message", "hello"));

            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var result = runInVirtualThread(() -> decodeAndHandle(callHandler, ctx, params));
            assertThat(result).isInstanceOf(CallToolResult.class);
        }
    }

    /**
     * Tool name validation follows
     * <a href="https://modelcontextprotocol.io/seps/986-specify-format-for-tool-names">SEP-986</a>:
     * 1-64 characters, case-sensitive, alphanumeric plus underscore, dash, dot, forward slash.
     */
    @ParameterizedTest
    @MethodSource("validToolNames")
    void shouldAcceptValidNameOnRegister(String name) {
        registry.register(tool -> tool.name(name), (ctx, request) -> ToolResult.text("ok"));
        assertThat(registry.find(name)).isPresent();
    }

    @Test
    void interfaceRegistersComposedSyncAndAsyncTools() {
        Tools api = registry;

        api.register(tool -> tool.name("builder-sync"), (ctx, request) -> ToolResult.text("sync"))
                .registerAsync(
                        tool -> tool.name("builder-async"),
                        (ctx, request) -> CompletableFuture.completedFuture(ToolResult.text("async")));

        assertThat(api.find("builder-sync")).isPresent();
        assertThat(api.find("builder-async")).isPresent();
        assertThat(api.descriptors()).extracting(ToolDescriptor::name).containsExactly("builder-async", "builder-sync");
        assertThatThrownBy(() -> api.descriptors().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(api.unregister("builder-sync")).isTrue();
        assertThat(api.unregister("builder-sync")).isFalse();
        assertThat(api.find("builder-sync")).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidToolNames")
    void shouldRejectInvalidNameOnRegister(String name) {
        assertThatThrownBy(() -> registry.register(tool -> tool.name(name), (ctx, request) -> ToolResult.text("ok")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> validToolNames() {
        return Stream.of(
                Arguments.of("valid-name"),
                Arguments.of("valid_name"),
                Arguments.of("valid.name"),
                Arguments.of("valid/name"),
                Arguments.of("VALID_NAME"),
                Arguments.of("tool123"),
                Arguments.of("admin.tools.list"),
                Arguments.of("user-profile/update"),
                Arguments.of("DATA_EXPORT_v2"),
                Arguments.of("a"),
                Arguments.of("a" + "b".repeat(63)));
    }

    private static Stream<Arguments> invalidToolNames() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("has space"),
                Arguments.of("has,comma"),
                Arguments.of("has@at"),
                Arguments.of("has#hash"),
                Arguments.of("has!bang"),
                Arguments.of("has%percent"),
                Arguments.of("has^caret"),
                Arguments.of("has&and"),
                Arguments.of("has*star"),
                Arguments.of("has(open"),
                Arguments.of("has)close"),
                Arguments.of("has[open"),
                Arguments.of("has]close"),
                Arguments.of("has{open"),
                Arguments.of("has}close"),
                Arguments.of("has;semi"),
                Arguments.of("has'quote"),
                Arguments.of("has\"quote"),
                Arguments.of("has<lt"),
                Arguments.of("has>gt"),
                Arguments.of("has?qmark"),
                Arguments.of("has+plus"),
                Arguments.of("has=eq"),
                Arguments.of("has~tilde"),
                Arguments.of("has`backtick"),
                Arguments.of("has\\backslash"),
                Arguments.of("a" + "b".repeat(64)));
    }

    @ParameterizedTest
    @CsvSource({"FORBIDDEN,forbidden", "OPTIONAL,optional", "REQUIRED,required"})
    void taskSupportSerializesToWireValue(TaskSupport enumValue, String wireValue) throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        registry.register(
                ToolDescriptor.builder().name("ts-tool").taskSupport(enumValue).build(),
                (context, request) -> ToolResult.text("ok"));

        var result = (ListToolsResult) decodeAndHandle(handlers.get("tools/list"), DefaultDispatchContext.noop(), null);
        var tool = result.tools().stream()
                .filter(t -> "ts-tool".equals(t.name()))
                .findFirst()
                .orElseThrow();
        assertThat(tool.execution()).isNotNull();
        assertThat(tool.execution().taskSupport()).isEqualTo(wireValue);
    }

    @Test
    void shouldFireOnChangeWhenToolAdded() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.register(testTool("new-tool", null, null));

        assertThat(callCount).hasValue(1);
    }

    @Test
    void shouldFireOnChangeWhenExistingToolRemoved() {
        registry.register(testTool("removable-tool", null, null));

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.unregister("removable-tool");

        assertThat(callCount).hasValue(1);
    }

    @Test
    void shouldNotFireOnChangeWhenRemovingNonExistentTool() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.unregister("does-not-exist");

        assertThat(callCount).hasValue(0);
    }

    @Test
    void shouldMapIconsFromDescriptorToProtocolModel() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        var icon = Icon.of("https://example.com/tool-icon.png", "image/png", List.of(), null);
        registry.register(
                ToolDescriptor.builder()
                        .name("icon-tool")
                        .description("Tool with icon")
                        .icons(List.of(icon))
                        .build(),
                (context, request) -> ToolResult.text("ok"));

        var listResult =
                (ListToolsResult) decodeAndHandle(handlers.get("tools/list"), DefaultDispatchContext.noop(), null);
        var tool = listResult.tools().stream()
                .filter(t -> "icon-tool".equals(t.name()))
                .findFirst()
                .orElseThrow();

        assertThat(tool.icons()).isNotNull().hasSize(1);
        assertThat(tool.icons().getFirst().src()).isEqualTo("https://example.com/tool-icon.png");
        assertThat(tool.icons().getFirst().mimeType()).isEqualTo("image/png");
    }

    @Test
    void findReturnsEmptyForMissingTool() {
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    @Test
    void findReturnsDescriptorForRegisteredTool() {
        registry.register(testTool("desc-tool", "test desc", null));
        var desc = registry.find("desc-tool").orElseThrow();
        assertThat(desc.name()).isEqualTo("desc-tool");
        assertThat(desc.description()).isEqualTo("test desc");
    }

    @Test
    void descriptorsReturnsAllDescriptors() {
        registry.register(testTool("t1", null, null));
        registry.register(testTool("t2", null, null));
        assertThat(registry.descriptors()).hasSize(2);
    }

    @Test
    void isEmptyReturnsTrueWhenEmpty() {
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void isEmptyReturnsFalseWhenNotEmpty() {
        registry.register(testTool("t", null, null));
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void listWithZeroLimitUsesDefaultPageSize() {
        registry.register(testTool("a", null, null));
        registry.register(testTool("b", null, null));
        var result = registry.list(0, null);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void listWithCursorSkipsPastCursor() {
        registry.register(testTool("alpha", null, null));
        registry.register(testTool("beta", null, null));
        registry.register(testTool("gamma", null, null));
        var result = registry.list(1, "alpha");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().name()).isEqualTo("beta");
    }

    @Test
    void listWithFilterExcludesMismatched() {
        registry.register(testTool("keep", null, null));
        registry.register(testTool("skip", null, null));
        var result = registry.list(50, null, d -> d.name().startsWith("k"));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().name()).isEqualTo("keep");
    }

    @Test
    void listWithCustomPageSize() {
        var reg = new DefaultToolRegistry(
                Jackson3JsonFactory.INSTANCE,
                FeatureConfig.builder().pageSize(1).build());
        reg.register(testTool("a", null, null));
        reg.register(testTool("b", null, null));
        var result = reg.list(0, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.nextCursor()).isEqualTo("a");
    }

    @Test
    void registerIsNoOpWhenToolsCapabilityIsOff() {
        var reg = new DefaultToolRegistry(
                Jackson3JsonFactory.INSTANCE, FeatureConfig.builder().off().build());
        var changeCount = new AtomicInteger();
        reg.onChange(changeCount::incrementAndGet);

        reg.register(testTool("a", null, null));

        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.find("a")).isEmpty();
        assertThat(reg.descriptors()).isEmpty();
        assertThat(changeCount).hasValue(0);
    }

    @Test
    void listReturnsCursorWhenMoreItemsAvailable() {
        registry.register(testTool("a", null, null));
        registry.register(testTool("b", null, null));
        var result = registry.list(1, null);
        assertThat(result.nextCursor()).isEqualTo("a");
    }

    @Test
    void listReturnsNullCursorWhenAllItemsReturned() {
        registry.register(testTool("a", null, null));
        var result = registry.list(10, null);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void registerThrowsOnNullDescriptor() {
        assertThatThrownBy(() -> registry.register((ToolDescriptor) null, (ctx, request) -> ToolResult.text("x")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ToolDescriptor");
    }

    @Test
    void internalRegisterThrowsOnNullDescriptor() {
        var handler = new ToolHandler() {
            @Override
            public ToolDescriptor descriptor() {
                return null;
            }

            @Override
            public CompletionStage<? extends ToolResult> handleAsync(InteractionContext context, ToolRequest request) {
                return CompletableFuture.completedStage(ToolResult.text("x"));
            }
        };
        assertThatThrownBy(() -> registry.register(handler))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ToolDescriptor");
    }

    @Test
    void findReturnsEmptyForMissingToolAfterOtherOperations() {
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    @Test
    void unregisterNonExistentToolReturnsFalse() {
        assertThat(registry.unregister("nonexistent")).isFalse();
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    @Test
    void registryResetBetweenTests() {
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void registerHandlersAddsBothMethods() {
        var handlers = new java.util.HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        assertThat(handlers).containsOnlyKeys("tools/list", "tools/call");
    }

    @Test
    void toolsListHandlerMethodName() {
        var handlers = new java.util.HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        assertThat(handlers.get("tools/list").method()).isEqualTo("tools/list");
    }

    @Test
    void toolsCallHandlerMethodName() {
        var handlers = new java.util.HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        assertThat(handlers.get("tools/call").method()).isEqualTo("tools/call");
    }

    @Test
    void asyncToolHandlerCompletesFromSeparateThread() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "async-tool-pool"));
        registry.registerAsync(
                builder -> builder.name("async-thread"),
                (ctx, request) -> CompletableFuture.supplyAsync(() -> ToolResult.text("from-thread"), executor));

        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("s-async-thread");
            session.activate();
            var callHandler = handlers.get("tools/call");
            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var params = Map.of("name", "async-thread", "arguments", Map.of());
            var stage = decodeAndHandleAsync(callHandler, ctx, params);
            var result = (CallToolResult) stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.content()).isNotEmpty();
            assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("from-thread");
        }
        executor.shutdown();
    }

    @Test
    void asyncToolHandlerInvalidArgumentExceptionMapsToInvalidRequest() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        registry.registerAsync(
                builder -> builder.name("invalid-arg-async"),
                (ctx, request) -> CompletableFuture.failedFuture(new InvalidArgumentException("arg", "bad input")));

        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("s-inv-arg");
            session.activate();
            var callHandler = handlers.get("tools/call");
            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var params = Map.of("name", "invalid-arg-async", "arguments", Map.of());
            var result = runInVirtualThread(() -> decodeAndHandle(callHandler, ctx, params));
            assertThat(result).isInstanceOf(ServerError.class);
            assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INVALID_PARAMS);
        }
    }

    @Test
    void syncToolHandlerReturnsResultThroughHandle() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        registry.register(
                configurer -> configurer.name("sync-handle").description("sync").inputSchema(TEST_SCHEMA.toString()),
                (ctx, request) -> {
                    var msg = request.arguments().stringValue("message");
                    return ToolResult.text(msg);
                });

        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("s-sync-handle");
            session.activate();
            var callHandler = handlers.get("tools/call");
            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var params = Map.of("name", "sync-handle", "arguments", Map.of("message", "hello-sync"));
            var result = runInVirtualThread(() -> decodeAndHandle(callHandler, ctx, params));
            assertThat(result).isInstanceOf(CallToolResult.class);
            var content = ((CallToolResult) result).content();
            assertThat(((TextContent) content.getFirst()).text()).isEqualTo("hello-sync");
        }
    }

    @Test
    void syncToolHandlerExceptionMapsToInternalError() throws Exception {
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registry, handlers);
        registry.register(desc -> desc.name("sync-fail").description("sync"), (ctx, request) -> {
            throw new IllegalStateException("boom");
        });

        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("s-sync-fail");
            session.activate();
            var callHandler = handlers.get("tools/call");
            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var params = Map.of("name", "sync-fail", "arguments", Map.of());
            var result = runInVirtualThread(() -> decodeAndHandle(callHandler, ctx, params));
            assertThat(result).isInstanceOf(ServerError.class);
            assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INTERNAL_ERROR);
        }
    }

    @Test
    void syncToolHandlerCheckedExceptionMapsToInternalError() throws Exception {
        try (ServerEngine server = newEngine(
                b -> {},
                s -> s.tools().register(desc -> desc.name("sync-checked-fail").description("sync"), (ctx, request) -> {
                    throw new IOException("boom");
                }))) {
            var session = server.createSession("s-sync-checked-fail");
            session.activate();
            var callHandler = server.getHandler("tools/call");
            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var params = Map.of("name", "sync-checked-fail", "arguments", Map.of());
            var result = runInVirtualThread(() -> decodeAndHandle(callHandler, ctx, params));
            assertThat(result).isInstanceOf(ServerError.class);
            assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INTERNAL_ERROR);
        }
    }

    @Test
    void shouldFireOnChangeWhenToolReRegistered() {
        registry.register(testTool("re-register", null, null));

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.register(testTool("re-register", null, null));
        assertThat(callCount).hasValue(1);
    }

    // region: Schema root check

    @Test
    void shouldAcceptNullInputSchema() {
        registry.register(testTool("null-input", null, null));
        assertThat(registry.find("null-input")).isPresent();
    }

    @Test
    void shouldAcceptValidInputSchemaWithTypeObject() {
        registry.register(testTool("valid-input", null, TEST_SCHEMA));
        assertThat(registry.find("valid-input")).isPresent();
    }

    @Test
    void shouldRejectInputSchemaWithWrongRootType() {
        var schema = parseJson("""
            {"type":"string"}
            """);
        assertThatThrownBy(() -> registry.register(testTool("bad", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema")
                .hasMessageContaining("\"type\": \"object\"");
    }

    @Test
    void shouldRejectInputSchemaWithoutType() {
        var schema = parseJson("""
            {"properties":{}}
            """);
        assertThatThrownBy(() -> registry.register(testTool("no-type", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema")
                .hasMessageContaining("\"type\": \"object\"")
                .hasMessageContaining("missing \"type\"");
    }

    @Test
    void shouldRejectInputSchemaThatIsNotAnObject() {
        var schema = parseJson("\"just a string\"");
        assertThatThrownBy(() -> registry.register(testTool("not-obj", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema")
                .hasMessageContaining("\"type\": \"object\"");
    }

    @Test
    void shouldRejectNestedHeaderAnnotation() {
        // Tachyon-only restriction (not a conformance requirement): a nested x-mcp-header must be
        // rejected, not silently ignored.
        var schema = parseJson("""
            {"type":"object","properties":{
              "target":{"type":"object","properties":{
                "region":{"type":"string","x-mcp-header":"Region"}}}}}
            """);
        assertThatThrownBy(() -> registry.register(testTool("nested", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header")
                .hasMessageContaining("nested");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true", "{}", "[]", "null"})
    void shouldRejectNonStringHeaderAnnotationValue(String annotationValue) {
        var schema = parseJson("""
                {"type":"object","properties":{"p":{"type":"string","x-mcp-header":%s}}}
                """.formatted(annotationValue));
        assertThatThrownBy(() -> registry.register(testTool("non-string-annotation", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header");
    }

    @Test
    void shouldRejectNestedNonStringHeaderAnnotationValue() {
        var schema = parseJson("""
                {"type":"object","properties":{
                  "target":{"type":"object","properties":{
                    "region":{"type":"string","x-mcp-header":123}}}}}
                """);
        assertThatThrownBy(() -> registry.register(testTool("nested-non-string", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header");
    }

    @ParameterizedTest
    @ValueSource(strings = {"array", "object", "null", "number"})
    void shouldRejectHeaderAnnotationOnNonPrimitiveType(String type) {
        // SEP-2243: string/integer/boolean only; number's string form is not canonical.
        var schema = parseJson("""
                {"type":"object","properties":{"p":{"type":"%s","x-mcp-header":"P"}}}
                """.formatted(type));
        assertThatThrownBy(() -> registry.register(testTool("bad-type-" + type, null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header")
                .hasMessageContaining(type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "My Region", "Region:Primary", "Région"})
    void shouldRejectMalformedHeaderAnnotationName(String headerName) {
        // SEP-2243: the value must be a non-empty RFC 9110 token (1*tchar).
        var schema = parseJson("""
                {"type":"object","properties":{"p":{"type":"string","x-mcp-header":"%s"}}}
                """.formatted(headerName));
        assertThatThrownBy(() -> registry.register(testTool("bad-name", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header");
    }

    @Test
    void shouldRejectHeaderAnnotationNameWithControlCharacter() {
        // Tab is legal in a header *value* but not in a field-name token. It has to reach the parser
        // as a JSON escape: a literal control character is not valid inside a JSON string.
        var schema = parseJson("""
            {"type":"object","properties":{"p":{"type":"string","x-mcp-header":"Region\\u0009Tab"}}}
            """);
        assertThatThrownBy(() -> registry.register(testTool("tab-name", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header");
    }

    @Test
    void shouldAcceptPropertyNamedLikeTheHeaderKeyword() {
        // A "properties" key is an author-chosen name, not a schema keyword, so it must not be
        // mistaken for an annotation sitting off the properties chain.
        var schema = parseJson("""
            {"type":"object","properties":{"x-mcp-header":{"type":"string"}}}
            """);
        registry.register(testTool("keyword-named-property", null, schema));
        assertThat(registry.find("keyword-named-property")).isPresent();
    }

    @Test
    void shouldRejectDuplicateHeaderAnnotationNamesIgnoringCase() {
        // Two properties on one header make it ambiguous for an intermediary.
        var schema = parseJson("""
            {"type":"object","properties":{
              "a":{"type":"string","x-mcp-header":"Region"},
              "b":{"type":"string","x-mcp-header":"REGION"}}}
            """);
        assertThatThrownBy(() -> registry.register(testTool("dup-header", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header")
                .hasMessageContaining("'a'")
                .hasMessageContaining("'b'")
                .hasMessageContaining("unique ignoring case");
    }

    @Test
    void shouldRejectHeaderAnnotationInsideArrayItems() {
        // Also below the top level, and there is no single argument to mirror either.
        var schema = parseJson("""
            {"type":"object","properties":{
              "rows":{"type":"array","items":{"type":"object","properties":{
                "region":{"type":"string","x-mcp-header":"Region"}}}}}}
            """);
        assertThatThrownBy(() -> registry.register(testTool("unreachable", null, schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header");
    }

    @Test
    void shouldAcceptOutputSchemaWithScalarRootType() {
        var outputSchema = parseJson("""
            {"type":"string"}
            """);
        registry.register(
                ToolDescriptor.builder()
                        .name("scalar-output")
                        .outputSchema(outputSchema.toString())
                        .build(),
                (context, request) -> ToolResult.text("x"));
        assertThat(registry.find("scalar-output")).isPresent();
    }

    @Test
    void shouldAcceptOutputSchemaWithArrayRootType() {
        var outputSchema = parseJson("""
            {"type":"array","items":{"type":"integer"}}
            """);
        registry.register(
                ToolDescriptor.builder()
                        .name("array-output")
                        .outputSchema(outputSchema.toString())
                        .build(),
                (context, request) -> ToolResult.text("x"));
        assertThat(registry.find("array-output")).isPresent();
    }

    @Test
    void shouldRejectOutputSchemaThatIsNotAnObject() {
        var outputSchema = parseJson("\"just a string\"");
        assertThatThrownBy(() -> registry.register(
                        ToolDescriptor.builder()
                                .name("bad-output")
                                .outputSchema(outputSchema.toString())
                                .build(),
                        (context, request) -> ToolResult.text("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputSchema")
                .hasMessageContaining("must be a JSON Schema object");
    }

    @Test
    void shouldAcceptValidOutputSchema() {
        var outputSchema = parseJson("""
            {"type":"object","properties":{"result":{"type":"string"}}}
            """);
        registry.register(
                ToolDescriptor.builder()
                        .name("valid-output")
                        .outputSchema(outputSchema.toString())
                        .build(),
                (context, request) -> ToolResult.text("ok"));
        assertThat(registry.find("valid-output")).isPresent();
    }

    // endregion

    // region: Structured value conversion for output validation

    @Test
    void shouldConvertPlainJavaMapEntriesBeforeOutputValidation() throws Exception {
        // language=json
        var outputSchema = parseJson("""
            {"type":"object","properties":{"message":{"type":"string"},"count":{"type":"integer"}},"required":["message","count"]}
            """);
        var registryVal = new DefaultToolRegistry(
                Jackson3JsonFactory.INSTANCE, FeatureConfig.builder().build());
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registryVal, handlers);
        registryVal.register(
                ToolDescriptor.builder()
                        .name("structured-out")
                        .description("test")
                        .outputSchema(outputSchema.toString())
                        .build(),
                (context, request) -> {
                    // Map with plain Java values, not JsonNode
                    return ToolResult.structured(Map.of("message", "hello", "count", 42), "text fallback");
                });

        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("s-struct-out");
            session.activate();
            var callHandler = handlers.get("tools/call");
            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var params = Map.of("name", "structured-out", "arguments", Map.of());
            var result = runInVirtualThread(() -> decodeAndHandle(callHandler, ctx, params));
            assertThat(result).isInstanceOf(CallToolResult.class);
            // structuredContent should contain both "message" and "count"
            assertThat(((CallToolResult) result).structuredContent()).isNotNull();
            assertThat(properties(((CallToolResult) result).structuredContent()))
                    .containsKeys("message", "count");
        }
    }

    @Test
    void shouldConvertMixedJavaAndJsonNodeEntries() throws Exception {
        var registryVal = new DefaultToolRegistry(
                Jackson3JsonFactory.INSTANCE, FeatureConfig.builder().build());
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        registerHandlers(registryVal, handlers);
        registryVal.register(
                ToolDescriptor.builder().name("mixed-out").description("test").build(), (context, request) -> {
                    var jsonNodeVal = tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("json-val");
                    // Mixed map: one JsonNode value, one plain String value
                    return ToolResult.structured(
                            Map.of("jsonField", jsonNodeVal, "plainField", "plain-val"), "fallback");
                });

        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("s-mixed");
            session.activate();
            var callHandler = handlers.get("tools/call");
            var ctx = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
            ctx.setSession(session);
            var params = Map.of("name", "mixed-out", "arguments", Map.of());
            var result = runInVirtualThread(() -> decodeAndHandle(callHandler, ctx, params));
            assertThat(result).isInstanceOf(CallToolResult.class);
            assertThat(((CallToolResult) result).structuredContent()).isNotNull();
            assertThat(properties(((CallToolResult) result).structuredContent()))
                    .containsOnlyKeys("jsonField", "plainField");
        }
    }

    // Rejecting output that fails outputSchema validation is covered end-to-end by
    // SchemaValidationTest.shouldRejectToolCallWithInvalidStructuredOutput (e2e) -- no unit test
    // here per this project's "drop unit if E2E already covers it" rule.

    // endregion

    private static ToolHandler testTool(String name, @Nullable String description, @Nullable JsonNode schema) {
        return new AbstractToolHandler(ToolDescriptor.builder()
                .name(name)
                .description(description)
                .inputSchema(schema != null ? schema.toString() : null)
                .build()) {
            @Override
            public ToolResult handle(InteractionContext context, ToolRequest request) {
                return ToolResult.text("ok");
            }
        };
    }

    @Test
    void shouldRejectRegistrationWithMalformedJsonSchema() {
        assertThatThrownBy(() -> registry.register(
                        builder -> builder.name("bad-tool").description("desc").inputSchema("not-json"),
                        (ctx, request) -> ToolResult.text("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-json");
    }

    @Test
    void shouldRejectRegistrationWithNonObjectStringSchema() {
        assertThatThrownBy(() -> registry.register(
                        builder -> builder.name("bad-string")
                                .description("desc")
                                .inputSchema("{\"type\":\"array\"}")
                                .outputSchema("{\"type\":\"string\"}"),
                        (ctx, request) -> ToolResult.text("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema")
                .hasMessageContaining("\"type\": \"object\"");
    }

    @Test
    void shouldAcceptRegistrationWithValidStringSchemas() {
        registry.register(
                builder -> builder.name("good-string")
                        .description("desc")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}")
                        .outputSchema("{\"type\":\"object\",\"properties\":{\"y\":{\"type\":\"integer\"}}}"),
                (ctx, request) -> ToolResult.text("ok"));
        assertThat(registry.find("good-string")).isPresent();
    }
}
