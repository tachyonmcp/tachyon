/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.prompts;

import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandle;
import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static dev.tachyonmcp.core.test.VirtualThreads.runInVirtualThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.prompts.Prompts;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.GetPromptResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListPromptsResult;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.config.FeatureConfig;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionRegistry;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultPromptRegistryTest {

    private final ServerEngine server = newEngine(b -> {});
    private final DefaultPromptRegistry registry =
            new DefaultPromptRegistry(FeatureConfig.builder().build());
    private final HashMap<String, RpcMethodHandler<?, ?>> handlers = new HashMap<>();

    private static PromptDescriptor prompt(String name) {
        return PromptDescriptor.of(name, null);
    }

    @BeforeEach
    void setUp() {
        PromptMethodHandlers.register(handlers, registry, new SubscriptionRegistry(server), JsonSchemaValidator.noop());
    }

    private Object getPrompt(Object params) throws Exception {
        return runInVirtualThread(
                () -> decodeAndHandle(handlers.get("prompts/get"), DefaultDispatchContext.stateless(server), params));
    }

    @Test
    void shouldReturnEmptyListWhenNoPromptsRegistered() throws Exception {
        var result = decodeAndHandle(handlers.get("prompts/list"), DefaultDispatchContext.stateless(server), null);

        assertThat(result).isInstanceOf(ListPromptsResult.class);
        assertThat(((ListPromptsResult) result).prompts()).isEmpty();
    }

    @Test
    void listWithZeroLimitUsesDefaultPageSize() {
        registry.register(prompt("p1"), List.of());
        registry.register(prompt("p2"), List.of());
        var result = registry.list(0, null);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void listWithCursorSkipsPastCursor() {
        registry.register(prompt("alpha"), List.of());
        registry.register(prompt("beta"), List.of());
        registry.register(prompt("gamma"), List.of());
        var result = registry.list(1, "alpha");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().name()).isEqualTo("beta");
    }

    @Test
    void listReturnsCursorWhenMoreItemsAvailable() {
        registry.register(prompt("a"), List.of());
        registry.register(prompt("b"), List.of());
        var result = registry.list(1, null);
        assertThat(result.nextCursor()).isEqualTo("a");
    }

    @Test
    void listReturnsNullCursorWhenAllItemsReturned() {
        registry.register(prompt("a"), List.of());
        var result = registry.list(10, null);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void listWithCustomPageSize() {
        var reg = new DefaultPromptRegistry(FeatureConfig.builder().pageSize(1).build());
        reg.register(prompt("a"), List.of());
        reg.register(prompt("b"), List.of());
        var result = reg.list(0, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.nextCursor()).isEqualTo("a");
    }

    @Test
    void registerIsNoOpWhenPromptsCapabilityIsOff() {
        var reg = new DefaultPromptRegistry(FeatureConfig.builder().off().build());
        var changeCount = new AtomicInteger();
        reg.onChange(changeCount::incrementAndGet);

        reg.register(prompt("a"), List.of());

        assertThat(reg.find("a")).isEmpty();
        assertThat(reg.descriptors()).isEmpty();
        assertThat(changeCount).hasValue(0);
    }

    @Test
    void interfaceDefaultBuilderOverloadsRegisterPrompts() {
        Prompts api = registry;

        api.register(prompt -> prompt.name("static"), List.of(PromptMessage.user("static")))
                .register(
                        prompt -> prompt.name("sync"),
                        (ctx, request) -> PromptResult.messages(List.of(PromptMessage.user("sync"))))
                .registerAsync(
                        prompt -> prompt.name("async"),
                        (ctx, request) -> CompletableFuture.completedFuture(
                                PromptResult.messages(List.of(PromptMessage.user("async")))));

        assertThat(api.descriptors()).extracting(PromptDescriptor::name).containsExactly("async", "static", "sync");
        assertThat(api.find("sync")).isPresent();
        assertThat(api.find("missing")).isEmpty();
        assertThat(api.unregister("sync")).isTrue();
        assertThat(api.unregister("sync")).isFalse();
        assertThat(api.find("sync")).isEmpty();
    }

    @Test
    void shouldReturnErrorWhenPromptNotFound() throws Exception {
        var result = getPrompt(Map.of("name", "nonexistent"));

        assertThat(result).isInstanceOf(ServerError.class);
        assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void shouldReturnErrorWhenPromptNameMissing() {
        assertThatThrownBy(() -> getPrompt(Map.of())).isInstanceOf(RequestMappingException.class);
    }

    @Test
    void shouldReturnPromptByName() throws Exception {
        registry.register(PromptDescriptor.of("greeting", "A greeting prompt"), List.of(PromptMessage.user("Hello!")));

        var result = getPrompt(Map.of("name", "greeting"));

        assertThat(result).isInstanceOf(GetPromptResult.class);
        assertThat(((GetPromptResult) result).description()).isEqualTo("A greeting prompt");
    }

    @Test
    void shouldReturnAllRegisteredPrompts() throws Exception {
        registry.register(PromptDescriptor.of("prompt-1", "First prompt"), List.of());
        registry.register(PromptDescriptor.of("prompt-2", "Second prompt"), List.of());

        var result = (ListPromptsResult)
                decodeAndHandle(handlers.get("prompts/list"), DefaultDispatchContext.stateless(server), null);

        assertThat(result.prompts()).hasSize(2);
    }

    @Test
    void shouldFireOnChangeWhenPromptAdded() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.register(PromptDescriptor.of("greeting", "A greeting prompt"), List.of());

        assertThat(callCount).hasValue(1);
    }

    @Test
    void shouldFireOnChangeWhenExistingPromptRemoved() {
        registry.register(PromptDescriptor.of("greeting", "A greeting prompt"), List.of());

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.unregister("greeting");

        assertThat(callCount).hasValue(1);
    }

    @Test
    void shouldNotFireOnChangeWhenRemovingNonExistentPrompt() {
        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.unregister("does-not-exist");

        assertThat(callCount).hasValue(0);
    }

    @Test
    void shouldReplacePromptAndFireOnChangeWhenAddedWithSameName() {
        registry.register(PromptDescriptor.of("greeting", "Original"), List.of());

        var callCount = new AtomicInteger(0);
        registry.onChange(callCount::incrementAndGet);

        registry.register(PromptDescriptor.of("greeting", "Updated"), List.of());

        assertThat(callCount).hasValue(1);
        assertThat(registry.find("greeting"))
                .get()
                .extracting(PromptDescriptor::description)
                .isEqualTo("Updated");
        assertThat(registry.descriptors()).hasSize(1);
    }

    @Test
    void shouldMapAllDescriptorFieldsToProtocolModel() throws Exception {
        var icon = Icon.of("https://example.com/prompt-icon.svg", "image/svg+xml", List.of(), null);
        var arg = PromptArgument.of("topic", null, "The topic", true);
        var descriptor =
                PromptDescriptor.of("full-prompt", "Full description", "Full Title", List.of(arg), null, List.of(icon));
        registry.register(descriptor, List.of(PromptMessage.user("Hello")));

        var result = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListPromptsResult)
                decodeAndHandle(handlers.get("prompts/list"), DefaultDispatchContext.stateless(server), null);
        var prompt = result.prompts().getFirst();

        assertThat(prompt.name()).isEqualTo("full-prompt");
        assertThat(prompt.description()).isEqualTo("Full description");
        assertThat(prompt.title()).isEqualTo("Full Title");
        assertThat(prompt.arguments()).hasSize(1);
        assertThat(prompt.arguments().getFirst().name()).isEqualTo("topic");
        assertThat(prompt.arguments().getFirst().required()).isTrue();
        assertThat(prompt.icons()).isNotNull().hasSize(1);
        assertThat(prompt.icons().getFirst().src()).isEqualTo("https://example.com/prompt-icon.svg");
    }

    @Test
    void shouldReturnInvalidParamsForBadArgumentsWithoutSchema() {
        registry.register(PromptDescriptor.of("no-schema", "No schema prompt"), List.of(PromptMessage.user("Hi")));

        assertThatThrownBy(() -> getPrompt(Map.of("name", "no-schema", "arguments", List.of(1, 2, 3))))
                .isInstanceOf(RequestMappingException.class)
                .extracting(e -> ((RequestMappingException) e).error().kind())
                .isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void shouldUseDynamicHandlerForMessages() throws Exception {
        registry.register(
                PromptDescriptor.of("dynamic", "Dynamic prompt"),
                (ctx, request) -> PromptResult.messages(
                        List.of(PromptMessage.user("args=" + request.arguments().json()))));

        var result = (GetPromptResult) getPrompt(Map.of("name", "dynamic"));

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst().content().toString()).contains("args=");
    }
}
