/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.test;

import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public class TestUtils {

    private TestUtils() {}

    /** Runs a handler's full decode-then-handle pipeline, for tests invoking a handler directly. */
    public static <I, O> O decodeAndHandle(
            RpcMethodHandler<I, O> handler, DispatchContext context, @Nullable Object rawParams) throws Exception {
        I decoded = handler.decode(context, rawParams);
        return handler.handle(context, decoded);
    }

    /** Runs a handler's full decode-then-handleAsync pipeline, for tests invoking a handler directly. */
    public static <I, O> CompletionStage<O> decodeAndHandleAsync(
            RpcMethodHandler<I, O> handler, DispatchContext context, @Nullable Object rawParams) throws Exception {
        I decoded = handler.decode(context, rawParams);
        return handler.handleAsync(context, decoded);
    }

    public static ServerEngine newEngine(Consumer<ServerBuilder> configurer) {
        return newEngine(configurer, server -> {});
    }

    public static ServerEngine newEngine(Consumer<ServerBuilder> configurer, Consumer<TachyonServer> registrar) {
        var builder = TachyonServer.builder();
        configurer.accept(builder);
        var server = builder.build();
        try {
            registrar.accept(server);
        } catch (RuntimeException | Error failure) {
            try {
                server.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return (ServerEngine) server;
    }

    public static JsonNode parseJson(String json) {
        return JsonUtils.parseJsonNode(json);
    }

    /** The node's properties as an ordered map, for AssertJ map assertions; empty for {@code null}. */
    public static Map<String, JsonNode> properties(@Nullable ObjectNode node) {
        var map = new LinkedHashMap<String, JsonNode>();
        if (node != null) {
            node.properties().forEach(property -> map.put(property.getKey(), property.getValue()));
        }
        return map;
    }
}
