/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.completions;

import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandle;
import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static dev.tachyonmcp.core.test.VirtualThreads.runInVirtualThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.completions.Completions;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.config.FeatureConfig;
import dev.tachyonmcp.core.server.config.ResourcesConfig;
import dev.tachyonmcp.core.server.features.prompts.DefaultPromptRegistry;
import dev.tachyonmcp.core.server.features.resources.DefaultResourceRegistry;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DefaultCompletionRegistryTest {

    private final ServerEngine server = newEngine(b -> {});
    private final CompletionRegistry registry = new DefaultCompletionRegistry();
    private final Completions completions = registry;
    private final HashMap<String, RpcMethodHandler<?, ?>> handlers = new HashMap<>();

    public DefaultCompletionRegistryTest() {
        CompletionMethodHandlers.register(
                handlers,
                (DefaultCompletionRegistry) registry,
                new DefaultPromptRegistry(FeatureConfig.builder().build()),
                new DefaultResourceRegistry(server, ResourcesConfig.builder().build()));
    }

    private static final JsonMapper JSON = new JsonMapper();

    private static JsonNode toJson(Object result) {
        return JSON.valueToTree(result);
    }

    private static List<String> texts(JsonNode array) {
        var result = new ArrayList<String>(array.size());
        array.forEach(n -> result.add(n.stringValue()));
        return result;
    }

    private Object complete(HashMap<String, RpcMethodHandler<?, ?>> handlers, Object params) throws Exception {
        return runInVirtualThread(() ->
                decodeAndHandle(handlers.get("completion/complete"), DefaultDispatchContext.stateless(server), params));
    }

    @Test
    void registersAndUnregistersSyncAndAsyncFunctions() {
        completions
                .registerForPrompt("greeting", (ctx, request) -> CompletionResult.of(List.of("sync")))
                .registerForPromptAsync(
                        "async-prompt",
                        (ctx, request) -> CompletableFuture.completedFuture(CompletionResult.of(List.of("async"))));
        completions
                .registerForResource("file:///{path}", (ctx, request) -> CompletionResult.of(List.of("resource")))
                .registerForResourceAsync(
                        "file:///async/{path}",
                        (ctx, request) ->
                                CompletableFuture.completedFuture(CompletionResult.of(List.of("resource-async"))));

        assertThat(completions.unregisterForPrompt("greeting")).isTrue();
        assertThat(completions.unregisterForPrompt("greeting")).isFalse();
        assertThat(completions.unregisterForPrompt("async-prompt")).isTrue();

        assertThat(completions.unregisterForResource("file:///{path}")).isTrue();
        assertThat(completions.unregisterForResource("file:///{path}")).isFalse();
        assertThat(completions.unregisterForResource("file:///async/{path}")).isTrue();
    }

    @Test
    void isEmptyReflectsBothNamespaces() {
        assertThat(registry.isEmpty()).isTrue();

        registry.registerForPrompt("greeting", (ctx, request) -> CompletionResult.of(List.of()));
        assertThat(registry.isEmpty()).isFalse();

        registry.unregisterForPrompt("greeting");
        registry.registerForResource("file:///{path}", (ctx, request) -> CompletionResult.of(List.of()));
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void completesPromptArgumentUsingRegisteredHandler() throws Exception {
        completions.registerForPrompt(
                "code_review",
                (ctx, request) -> CompletionResult.of(List.of("python", "pytorch", "pyside"), 10L, true));

        var result = complete(
                handlers,
                Map.of(
                        "ref", Map.of("type", "ref/prompt", "name", "code_review"),
                        "argument", Map.of("name", "language", "value", "py")));

        var completion = toJson(result).get("completion");
        assertThat(texts(completion.get("values"))).containsExactly("python", "pytorch", "pyside");
        assertThat(completion.get("total").doubleValue()).isEqualTo(10.0);
        assertThat(completion.get("hasMore").booleanValue()).isTrue();
    }

    @Test
    void completesResourceArgumentUsingRegisteredHandler() throws Exception {
        completions.registerForResource("file:///{path}", (ctx, request) -> CompletionResult.of(List.of("a.txt")));

        var result = complete(
                handlers,
                Map.of(
                        "ref", Map.of("type", "ref/resource", "uri", "file:///{path}"),
                        "argument", Map.of("name", "path", "value", "a")));

        assertThat(texts(toJson(result).get("completion").get("values"))).containsExactly("a.txt");
    }

    @Test
    void passesResolvedArgumentsFromContext() throws Exception {
        registry.registerForPrompt(
                "code_review",
                (ctx, request) ->
                        CompletionResult.of(List.of(request.resolvedArguments().get("language") + "-flask")));

        var result = complete(
                handlers,
                Map.of(
                        "ref", Map.of("type", "ref/prompt", "name", "code_review"),
                        "argument", Map.of("name", "framework", "value", "fla"),
                        "context", Map.of("arguments", Map.of("language", "python"))));

        assertThat(texts(toJson(result).get("completion").get("values"))).containsExactly("python-flask");
    }

    @Test
    void returnsEmptyCompletionWhenNoHandlerRegisteredForRef() throws Exception {
        var result = complete(
                handlers,
                Map.of(
                        "ref", Map.of("type", "ref/prompt", "name", "unknown"),
                        "argument", Map.of("name", "language", "value", "py")));

        var completion = toJson(result).get("completion");
        assertThat(texts(completion.get("values"))).isEmpty();
        assertThat(completion.get("hasMore").booleanValue()).isFalse();
    }

    @Test
    void truncatesToMaxValuesAndForcesHasMore() throws Exception {
        var values =
                java.util.stream.IntStream.range(0, 150).mapToObj(i -> "v" + i).toList();
        registry.registerForPrompt("many", (ctx, request) -> CompletionResult.of(values));

        var result = complete(
                handlers,
                Map.of(
                        "ref", Map.of("type", "ref/prompt", "name", "many"),
                        "argument", Map.of("name", "arg", "value", "")));

        var completion = toJson(result).get("completion");
        assertThat(texts(completion.get("values"))).hasSize(100);
        assertThat(completion.get("hasMore").booleanValue()).isTrue();
    }

    @Test
    void returnsInvalidParamsWhenRefMissing() {
        assertThatThrownBy(() -> complete(handlers, Map.of("argument", Map.of("name", "a", "value", "b"))))
                .isInstanceOf(RequestMappingException.class)
                .extracting(e -> ((RequestMappingException) e).error().kind())
                .isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void returnsInvalidParamsWhenArgumentMissing() {
        assertThatThrownBy(() -> complete(handlers, Map.of("ref", Map.of("type", "ref/prompt", "name", "code_review"))))
                .isInstanceOf(RequestMappingException.class)
                .extracting(e -> ((RequestMappingException) e).error().kind())
                .isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void returnsInvalidParamsForUnknownRefType() {
        assertThatThrownBy(() -> complete(
                        handlers,
                        Map.of(
                                "ref", Map.of("type", "ref/unknown", "name", "x"),
                                "argument", Map.of("name", "a", "value", "b"))))
                .isInstanceOf(RequestMappingException.class)
                .extracting(e -> ((RequestMappingException) e).error().kind())
                .isEqualTo(ServerError.Kind.INVALID_PARAMS);
    }

    @Test
    void returnsInternalErrorWhenHandlerThrows() throws Exception {
        registry.registerForPrompt("boom", (ctx, request) -> {
            throw new RuntimeException("kaboom");
        });

        var result = complete(
                handlers,
                Map.of(
                        "ref", Map.of("type", "ref/prompt", "name", "boom"),
                        "argument", Map.of("name", "a", "value", "b")));

        assertThat(result).isInstanceOf(ServerError.class);
        assertThat(((ServerError) result).kind()).isEqualTo(ServerError.Kind.INTERNAL_ERROR);
    }
}
