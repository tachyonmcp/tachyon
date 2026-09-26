/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandle;
import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static dev.tachyonmcp.core.test.VirtualThreads.runInVirtualThread;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceRequest;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ReadResourceRequestParams;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.config.ResourcesConfig;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ResourceRequestDispatchTest {

    @Test
    void shouldPassRequestMetaToResourceFn() throws Exception {
        var server = newEngine(builder -> {});
        var registry =
                new DefaultResourceRegistry(server, ResourcesConfig.builder().build());
        var handlers = new HashMap<String, RpcMethodHandler<?, ?>>();
        ResourceMethodHandlers.register(handlers, registry);
        var captured = new AtomicReference<@Nullable ResourceRequest>();
        registry.register(
                ResourceDescriptor.of("meta-request", "test://meta-request", null, "text/plain"), (ctx, request) -> {
                    captured.set(request);
                    return TextResourceContents.of(request.uri(), "meta", "text/plain");
                });
        var meta = JsonNodeFactory.instance.objectNode().put("trace-id", "trace-42");

        runInVirtualThread(() -> decodeAndHandle(
                handlers.get("resources/read"),
                DefaultDispatchContext.stateless(server),
                ReadResourceRequestParams.builder()
                        .uri("test://meta-request")
                        ._meta(meta)
                        .build()));

        assertThat(captured.get().uri()).isEqualTo("test://meta-request");
        assertThat(captured.get().params()).isEmpty();
        assertThat(captured.get().uriTemplate()).isNull();
        assertThat(captured.get().meta()).isEqualTo(Map.of("trace-id", "trace-42"));
    }
}
