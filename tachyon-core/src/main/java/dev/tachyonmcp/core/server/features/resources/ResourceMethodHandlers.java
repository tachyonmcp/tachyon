/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.HandlerFutures;
import dev.tachyonmcp.api.server.features.resources.ResourceRequest;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSON-RPC adapters for resource operations. */
public final class ResourceMethodHandlers {

    private ResourceMethodHandlers() {}

    public static void register(Map<String, RpcMethodHandler<?, ?>> handlers, DefaultResourceRegistry registry) {
        handlers.put("resources/list", new ResourcesListHandler(registry));
        handlers.put("resources/templates/list", new ResourcesTemplatesListHandler(registry));
        handlers.put("resources/read", new ResourcesReadHandler(registry));
        handlers.put("resources/subscribe", new ResourcesSubscribeHandler(registry));
        handlers.put("resources/unsubscribe", new ResourcesUnsubscribeHandler(registry));
    }

    private record ResourcesListHandler(DefaultResourceRegistry registry)
            implements RpcMethodHandler<ProtocolRequestMapper.PageRequest, Object> {

        @Override
        public String method() {
            return "resources/list";
        }

        @Override
        public ProtocolRequestMapper.PageRequest decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().page(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, ProtocolRequestMapper.PageRequest page) {
            var paginated = registry.list(page.limit(), page.cursor(), descriptor -> {
                var extensionId = descriptor.extensionId();
                return extensionId == null || context.isExtensionEnabled(extensionId);
            });
            if (!paginated.cursorValid()) return ServerErrors.invalidParams("Invalid cursor");
            return context.responseMapper().listResourcesResult(paginated.items(), paginated.nextCursor());
        }
    }

    private record ResourcesTemplatesListHandler(DefaultResourceRegistry registry)
            implements RpcMethodHandler<ProtocolRequestMapper.PageRequest, Object> {

        @Override
        public String method() {
            return "resources/templates/list";
        }

        @Override
        public ProtocolRequestMapper.PageRequest decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().page(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, ProtocolRequestMapper.PageRequest page) {
            if (page.cursor() != null) {
                return ServerErrors.invalidParams("Invalid cursor");
            }
            final var visible = registry.templateDescriptors().stream()
                    .filter(descriptor -> {
                        var extensionId = descriptor.extensionId();
                        return extensionId == null || context.isExtensionEnabled(extensionId);
                    })
                    .toList();
            return context.responseMapper().listResourceTemplatesResult(visible, null);
        }
    }

    private record ResourcesReadHandler(DefaultResourceRegistry registry)
            implements RpcMethodHandler<ResourceRequest, Object> {

        private static final Logger logger = LoggerFactory.getLogger(ResourcesReadHandler.class);

        @Override
        public String method() {
            return "resources/read";
        }

        @Override
        public ResourceRequest decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().readResource(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, ResourceRequest mapped) throws Exception {
            return HandlerFutures.joinInterruptibly(handleAsync(context, mapped));
        }

        @Override
        public CompletionStage<Object> handleAsync(DispatchContext context, ResourceRequest mapped) {
            if (!DefaultResourceRegistry.isValidResourceUri(mapped.uri())) {
                return CompletableFuture.completedFuture(ServerErrors.invalidParams("Invalid resource URI"));
            }
            var entry = registry.getByUri(mapped.uri());
            if (entry != null) {
                var extensionId = entry.descriptor().extensionId();
                if (extensionId != null && !context.isExtensionEnabled(extensionId)) {
                    return CompletableFuture.completedFuture(ServerErrors.resourceNotFound("Resource not found"));
                }
                return readResult(
                        context,
                        mapped.uri(),
                        entry.privateCaching(),
                        () -> entry.fn().apply(context, mapped));
            }
            var match = registry.matchTemplate(mapped.uri());
            if (match == null) {
                return CompletableFuture.completedFuture(
                        ServerErrors.resourceNotFound("Resource not found", Map.of("uri", mapped.uri())));
            }
            var request = ResourceRequest.builder()
                    .uri(mapped.uri())
                    .params(match.params())
                    .uriTemplate(match.entry().descriptor().uriTemplate())
                    .meta(mapped.meta())
                    .inputResponses(mapped.inputResponses())
                    .requestState(mapped.requestState())
                    .build();
            return readResult(
                    context,
                    mapped.uri(),
                    match.entry().privateCaching(),
                    () -> match.entry().fn().apply(context, request));
        }

        private CompletionStage<Object> readResult(
                DispatchContext context,
                String uri,
                boolean privateCaching,
                Callable<CompletionStage<? extends ResourceContents>> invoker) {
            return HandlerFutures.invokeAndMap(
                    "Resource handler for '" + uri + "' returned a null CompletionStage",
                    invoker,
                    context.engine().executor(),
                    (contents, cause) -> {
                        if (cause != null) {
                            var error = ServerErrors.fromUnhandledException(cause, "Resource handler failed");
                            if (error.kind() == ServerError.Kind.INVALID_PARAMS) {
                                logger.debug("Resource handler rejected invalid params for '{}'", uri);
                            } else {
                                logger.error("Resource handler error for '{}'", uri, cause);
                            }
                            context.captureExceptionCause(cause);
                            return error;
                        }
                        return context.responseMapper().readResourceResult(List.of(contents), privateCaching);
                    });
        }
    }

    private record ResourcesSubscribeHandler(DefaultResourceRegistry registry)
            implements RpcMethodHandler<String, Object> {

        @Override
        public String method() {
            return "resources/subscribe";
        }

        @Override
        public String decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().resourceUri(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, String uri) {
            if (!DefaultResourceRegistry.isValidResourceUri(uri)) {
                return ServerErrors.invalidParams("Invalid resource URI");
            }
            var session = context.session();
            if (session == null) {
                return ServerErrors.invalidRequest("resources/subscribe requires a session");
            }
            registry.subscribe(uri, session.id());
            return context.responseMapper().emptyResult();
        }
    }

    private record ResourcesUnsubscribeHandler(DefaultResourceRegistry registry)
            implements RpcMethodHandler<String, Object> {

        @Override
        public String method() {
            return "resources/unsubscribe";
        }

        @Override
        public String decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().resourceUri(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, String uri) {
            if (!DefaultResourceRegistry.isValidResourceUri(uri)) {
                return ServerErrors.invalidParams("Invalid resource URI");
            }
            var session = context.session();
            if (session == null) {
                return ServerErrors.invalidRequest("resources/unsubscribe requires a session");
            }
            registry.unsubscribe(uri, session.id());
            return context.responseMapper().emptyResult();
        }
    }
}
