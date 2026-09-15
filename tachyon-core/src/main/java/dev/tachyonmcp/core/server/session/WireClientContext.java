/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.ClientContext;
import dev.tachyonmcp.api.runtime.ElicitationRequest;
import dev.tachyonmcp.api.runtime.ElicitationResult;
import dev.tachyonmcp.api.runtime.ElicitationService;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.runtime.SamplingService;
import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts {@link InteractionContext#sendRequest(String, Object)} into the typed {@link
 * ElicitationService}/{@link SamplingService} surface, building wire-shaped params and parsing raw
 * JSON responses back into domain types.
 */
@InternalApi
public final class WireClientContext implements ClientContext, ElicitationService, SamplingService {

    private final InteractionContext ctx;

    public WireClientContext(InteractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public ElicitationService elicitation() {
        return this;
    }

    @Override
    @ExperimentalApi
    public SamplingService sampling() {
        return this;
    }

    @Override
    public CompletableFuture<ElicitationResult> create(ElicitationRequest request) {
        var params = new LinkedHashMap<String, Object>();
        params.put("mode", "form");
        params.put("message", request.message());
        params.put(
                "requestedSchema",
                JsonRpcCodec.readValue(request.requestedSchema().json()));
        return ctx.sendRequest("elicitation/create", params).thenApply(WireClientContext::toElicitationResult);
    }

    @Override
    public CompletableFuture<Args> createMessage(Args params) {
        return ctx.sendRequest("sampling/createMessage", params.asMap()).thenApply(WireClientContext::toArgs);
    }

    @SuppressWarnings("unchecked")
    private static ElicitationResult toElicitationResult(String json) {
        var map = (Map<String, Object>) JsonRpcCodec.readValue(json);
        var action = ElicitationResult.Action.valueOf(((String) map.get("action")).toUpperCase(Locale.ROOT));
        var content = map.get("content");
        if (action == ElicitationResult.Action.ACCEPT && !(content instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "elicitation/create response with action=ACCEPT must include an object 'content', got: " + content);
        }
        return ElicitationResult.builder()
                .action(action)
                .content(content instanceof Map<?, ?> cm ? Args.of((Map<String, ?>) cm) : null)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Args toArgs(String json) {
        return Args.of((Map<String, Object>) JsonRpcCodec.readValue(json));
    }
}
