/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.resources.ResourceRequest;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.features.Visibility;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionRegistry;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionTopic;
import dev.tachyonmcp.core.server.internal.HandlerFutures;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSON-RPC adapters for resource operations. */
public final class ResourceMethodHandlers {

    /** {@code subscriptions/listen} opt-in to {@code notifications/resources/list_changed}. */
    public static final SubscriptionTopic<Boolean> LIST_CHANGED = SubscriptionTopic.flag("resourcesListChanged");

    private static final SubscriptionTopic<Set<String>> REQUESTED_URIS =
            SubscriptionTopic.strings("resourceSubscriptions");

    /**
     * The resource URIs a {@code subscriptions/listen} stream asked for, and the ones it follows. The
     * acknowledgment echoes every requested URI, so a URI the caller may not see is acknowledged exactly
     * like one that does not exist; only the followed ones are ever notified.
     *
     * @param requested what the listener asked for, echoed in the acknowledgment
     * @param followed what it is notified about: {@code requested} without what it may not see
     */
    public record ResourceUris(Set<String> requested, Set<String> followed) {}

    private ResourceMethodHandlers() {}

    /**
     * Installs the resource methods and their {@code subscriptions/listen} topics.
     *
     * @return the {@code resourceSubscriptions} topic, which {@code notifications/resources/updated} is
     *     published to
     */
    public static SubscriptionTopic<ResourceUris> register(
            Map<String, RpcMethodHandler<?, ?>> handlers,
            DefaultResourceRegistry registry,
            SubscriptionRegistry subscriptions) {
        final var updated = new SubscriptionTopic<ResourceUris>(
                REQUESTED_URIS.filterKey(),
                (context, filter) -> {
                    var requested = REQUESTED_URIS.decoder().decode(context, filter);
                    return requested == null ? null : new ResourceUris(requested, requested);
                },
                (context, uris) -> new ResourceUris(
                        uris.requested(),
                        uris.requested().stream()
                                .filter(uri -> !registry.isHidden(uri, Visibility.to(context)))
                                .collect(Collectors.toUnmodifiableSet())),
                uris -> List.copyOf(uris.requested()));
        subscriptions.register(LIST_CHANGED);
        subscriptions.register(updated);
        handlers.put("resources/list", new ResourcesListHandler(registry));
        handlers.put("resources/templates/list", new ResourcesTemplatesListHandler(registry));
        handlers.put("resources/read", new ResourcesReadHandler(registry));
        handlers.put("resources/subscribe", new ResourcesSubscribeHandler(registry));
        handlers.put("resources/unsubscribe", new ResourcesUnsubscribeHandler(registry));
        return updated;
    }

    /** The answer for a resource the caller cannot read: the same whether it is unknown or hidden. */
    private static ServerError notFound(String uri) {
        return ServerErrors.resourceNotFound("Resource not found", Map.of("uri", uri));
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
            var paginated = registry.list(page.limit(), page.cursor(), Visibility.to(context));
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
                    .filter(Visibility.to(context))
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
            return HandlerFutures.joinInterruptible(handleAsync(context, mapped));
        }

        @Override
        public CompletionStage<Object> handleAsync(DispatchContext context, ResourceRequest mapped) {
            if (!DefaultResourceRegistry.isValidResourceUri(mapped.uri())) {
                return CompletableFuture.completedFuture(ServerErrors.invalidParams("Invalid resource URI"));
            }
            // A resource or template the caller may not see is not there: the answer is the unknown one.
            var entry = registry.getByUri(mapped.uri());
            if (entry != null && Visibility.of(context, entry.descriptor())) {
                return readResult(
                        context,
                        mapped.uri(),
                        entry.privateCaching(),
                        () -> entry.fn().apply(context, mapped));
            }
            var match = registry.matchTemplate(mapped.uri(), Visibility.to(context));
            if (match == null) {
                return CompletableFuture.completedFuture(notFound(mapped.uri()));
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
            // Accepted alike for a URI nothing names and one the caller may not see; only the second is
            // never followed, so no update about it reaches the caller.
            if (!registry.isHidden(uri, Visibility.to(context))) {
                registry.subscribe(uri, session.id());
            }
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
