/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.config.Mode;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.UriTemplateValue;
import dev.tachyonmcp.api.server.features.HandlerFutures;
import dev.tachyonmcp.api.server.features.PaginatedResult;
import dev.tachyonmcp.api.server.features.resources.AsyncResourceFn;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceFn;
import dev.tachyonmcp.api.server.features.resources.ResourceRequest;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.resources.Resources;
import dev.tachyonmcp.core.server.config.ResourcesConfig;
import dev.tachyonmcp.core.server.features.ChangeSupport;
import dev.tachyonmcp.core.server.features.Pagination;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for resources, templates, and subscriptions.
 */
@InternalApi
public class DefaultResourceRegistry implements Resources {

    private static final int MAX_RESOURCE_URI_LENGTH = 8_192;
    private static final Logger logger = LoggerFactory.getLogger(DefaultResourceRegistry.class);

    /** Name-sorted per {@code docs/architecture/guidance.md}; URI breaks ties between same-named resources. */
    private static final Comparator<ResourceDescriptor> RESOURCE_ORDER =
            Comparator.comparing(ResourceDescriptor::name).thenComparing(ResourceDescriptor::uri);

    /**
     * URI-keyed index of registered resources. URI is a resource's identity — see {@link
     * Resources#register}; distinct resources may share a {@code name}. Published atomically
     * through {@link #index} so readers always observe a consistent snapshot.
     */
    private record Index(Map<String, ResourceEntry> byUri) {
        static final Index EMPTY = new Index(Map.of());
    }

    private volatile Index index = Index.EMPTY;
    private final ReentrantLock writeLock = new ReentrantLock();

    private final ConcurrentHashMap<String, ResourceTemplateEntry> templates = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ServerEngine server;
    private final ResourcesConfig config;

    private final ChangeSupport changes = new ChangeSupport();

    private record SyncResourceFnAdapter(ResourceFn delegate) implements AsyncResourceFn {
        @Override
        public CompletionStage<? extends ResourceContents> apply(InteractionContext context, ResourceRequest request) {
            HandlerFutures.assumeVirtualThread();
            return HandlerFutures.completedOrFailed(() -> delegate.apply(context, request));
        }
    }

    /**
     * Creates a resource registry bound to the given server (for broadcasting subscription notifications).
     */
    public DefaultResourceRegistry(ServerEngine server, ResourcesConfig config) {
        this.server = server;
        this.config = config;
    }

    public void onChange(Runnable callback) {
        changes.onChange(callback);
    }

    /**
     * Notifies registered callbacks that the registry has changed.
     */
    private void fireOnChange() {
        changes.fireOnChange();
    }

    static boolean isValidResourceUri(String uri) {
        if (uri.isBlank() || uri.length() > MAX_RESOURCE_URI_LENGTH) {
            return false;
        }
        try {
            new URI(uri);
            return true;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    /**
     * Registers a resource descriptor and its handler.
     *
     * <p>If resource support is disabled, the descriptor is not registered. A resource's URI is its
     * identity — see {@link Resources#register}. Registering a URI already known under the same
     * name replaces that resource in place; registering it under a different name is rejected.
     *
     * @param descriptor the resource descriptor to register
     * @param fn the resource function
     * @return this registry
     * @throws IllegalArgumentException if the URI is already registered under a different name
     */
    @Override
    public DefaultResourceRegistry register(ResourceDescriptor descriptor, ResourceFn fn) {
        registerAsync(descriptor, new SyncResourceFnAdapter(fn));
        return this;
    }

    @Override
    public DefaultResourceRegistry registerAsync(ResourceDescriptor descriptor, AsyncResourceFn fn) {
        if (config.mode() == Mode.OFF) {
            logger.debug("Resource '{}' not registered: resources capability is OFF", descriptor.name());
            return this;
        }
        var entry = new ResourceEntry(descriptor, fn, privateCaching(fn));
        writeLock.lock();
        try {
            var current = index;
            var previous = current.byUri().get(descriptor.uri());
            if (previous != null && !previous.descriptor().name().equals(descriptor.name())) {
                throw new IllegalArgumentException("Resource URI '" + descriptor.uri()
                        + "' is already registered under name '"
                        + previous.descriptor().name() + "'");
            }
            if (entry.equals(previous)) {
                return this;
            }
            var newByUri = new HashMap<>(current.byUri());
            newByUri.put(descriptor.uri(), entry);
            index = new Index(Map.copyOf(newByUri));
        } finally {
            writeLock.unlock();
        }
        fireOnChange();
        return this;
    }

    /**
     * Removes a registered resource with the specified name.
     *
     * <p>{@code name} is not guaranteed unique — see {@link Resources#register}. If more than one
     * resource shares {@code name}, one of them is removed; which one is unspecified. Prefer {@link
     * #unregisterByUri} when the URI is known.
     *
     * @param name the resource name
     * @return {@code true} if a resource was removed, {@code false} if no resource was registered under the name
     */
    @Override
    public boolean unregister(String name) {
        var match = index.byUri().values().stream()
                .filter(e -> e.descriptor().name().equals(name))
                .findFirst();
        return match.map(e -> unregisterByUri(e.descriptor().uri())).orElse(false);
    }

    @Override
    public boolean unregisterByUri(String uri) {
        writeLock.lock();
        try {
            var current = index;
            var removed = current.byUri().get(uri);
            if (removed == null) {
                return false;
            }
            var newByUri = new HashMap<>(current.byUri());
            newByUri.remove(uri);
            dropSubscriptions(uri);
            index = new Index(Map.copyOf(newByUri));
        } finally {
            writeLock.unlock();
        }
        fireOnChange();
        return true;
    }

    /**
     * Finds a registered resource by name.
     *
     * <p>{@code name} is not guaranteed unique — see {@link Resources#register}. If more than one
     * resource shares {@code name}, one of them is returned; which one is unspecified. Prefer
     * {@link #findByUri} when the URI is known.
     *
     * @param name the resource name
     * @return the resource descriptor, or an empty optional if no resource has the specified name
     */
    @Override
    public Optional<ResourceDescriptor> find(String name) {
        return index.byUri().values().stream()
                .map(ResourceEntry::descriptor)
                .filter(d -> d.name().equals(name))
                .findFirst();
    }

    @Override
    public Optional<ResourceDescriptor> findByUri(String uri) {
        var entry = index.byUri.get(uri);
        return entry != null ? Optional.of(entry.descriptor()) : Optional.empty();
    }

    /**
     * Lists registered resource descriptors in ascending name order.
     *
     * @return the registered resource descriptors
     */
    @Override
    public List<ResourceDescriptor> descriptors() {
        return index.byUri().values().stream()
                .map(ResourceEntry::descriptor)
                .sorted(RESOURCE_ORDER)
                .toList();
    }

    /**
     * Lists registered resources in name order using cursor-based pagination.
     *
     * @param limit  the maximum number of resources to include; the configured page size is used when this value is not positive
     * @param cursor the cursor identifying the starting position, or {@code null} to start from the beginning
     * @return      the paginated resources and the cursor for the next page
     */
    public PaginatedResult<ResourceDescriptor> list(int limit, @Nullable String cursor) {
        int lim = limit > 0 ? limit : config.pageSize();
        var all = index.byUri().values().stream()
                .map(ResourceEntry::descriptor)
                .sorted(RESOURCE_ORDER)
                .toList();
        return Pagination.paginate(all, lim, cursor, DefaultResourceRegistry::resourceCursorKey);
    }

    public PaginatedResult<ResourceDescriptor> list(
            int limit, @Nullable String cursor, Predicate<ResourceDescriptor> filter) {
        int lim = limit > 0 ? limit : config.pageSize();
        var all = index.byUri().values().stream()
                .map(ResourceEntry::descriptor)
                .filter(filter)
                .sorted(RESOURCE_ORDER)
                .toList();
        return Pagination.paginate(all, lim, cursor, DefaultResourceRegistry::resourceCursorKey);
    }

    private static String resourceCursorKey(ResourceDescriptor descriptor) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(descriptor.name().getBytes(StandardCharsets.UTF_8))
                + encoder.encodeToString(descriptor.uri().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Finds the resource registered for the specified URI.
     *
     * @param uri the resource URI
     * @return the matching resource entry, or {@code null} if no resource is registered for the URI
     */
    @Nullable
    ResourceEntry getByUri(String uri) {
        return index.byUri().get(uri);
    }

    /**
     * Registers a resource template with its handler.
     *
     * <p>If resource support is disabled, the template is not registered. An exception is thrown when
     * a template with the same name already exists.
     *
     * @param descriptor the resource template descriptor
     * @param fn the resource function
     * @return this registry
     * @throws IllegalArgumentException if a template with the same name is already registered
     */
    @Override
    public Resources registerTemplate(ResourceTemplateDescriptor descriptor, ResourceFn fn) {
        return registerTemplateAsync(descriptor, new SyncResourceFnAdapter(fn));
    }

    @Override
    public Resources registerTemplateAsync(ResourceTemplateDescriptor descriptor, AsyncResourceFn fn) {
        if (config.mode() == Mode.OFF) {
            logger.debug("Resource template '{}' not registered: resources capability is OFF", descriptor.name());
            return this;
        }
        final var prevEntry =
                templates.putIfAbsent(descriptor.name(), ResourceTemplateEntry.of(descriptor, fn, privateCaching(fn)));
        if (prevEntry != null) {
            throw new IllegalArgumentException("Resource template '" + descriptor.name() + "' already exists");
        }
        fireOnChange();
        return this;
    }

    /**
     * Removes a registered resource template by name.
     *
     * @param name the name of the template to remove
     * @return {@code true} if a template was removed, {@code false} otherwise
     */
    @Override
    public boolean unregisterTemplate(String name) {
        var removed = templates.remove(name);
        if (removed != null) {
            fireOnChange();
            return true;
        }
        return false;
    }

    /**
     * Finds a registered resource template by name.
     *
     * @param name the template name
     * @return the matching resource template descriptor, or an empty optional if no template is registered with that name
     */
    @Override
    public Optional<ResourceTemplateDescriptor> findTemplate(String name) {
        var template = templates.get(name);
        return template != null ? Optional.of(template.descriptor()) : Optional.empty();
    }

    /**
     * Lists registered resource template descriptors in name order.
     *
     * @return the registered resource template descriptors sorted by name
     */
    @Override
    public List<ResourceTemplateDescriptor> templateDescriptors() {
        return templates.values().stream()
                .map(ResourceTemplateEntry::descriptor)
                .sorted(Comparator.comparing(ResourceTemplateDescriptor::name))
                .toList();
    }

    /**
     * Determines whether the registry contains no resources.
     *
     * @return {@code true} if the registry contains no resources, {@code false} otherwise
     */
    public boolean isEmpty() {
        return index.byUri().isEmpty();
    }

    private static boolean privateCaching(AsyncResourceFn fn) {
        return fn instanceof SyncResourceFnAdapter(ResourceFn delegate) && delegate instanceof PrivateCachingResourceFn;
    }

    record TemplateMatch(ResourceTemplateEntry entry, Map<String, UriTemplateValue> params) {}

    /**
     * Finds the most specific registered resource template matching the URI.
     *
     * @param uri the resource URI to match
     * @return the matching template and extracted parameters, or {@code null} if no template matches
     */
    @Nullable
    TemplateMatch matchTemplate(String uri) {
        return templates.values().stream()
                .sorted(Comparator.comparingInt((ResourceTemplateEntry t) -> -UriTemplatePatterns.EXPRESSION
                                .matcher(t.descriptor().uriTemplate())
                                .replaceAll("")
                                .length())
                        .thenComparing(it -> it.descriptor().name()))
                .map(template -> {
                    try {
                        return new TemplateMatch(
                                template, template.uriTemplate().parse(uri));
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns whether the given session is subscribed to the resource URI.
     */
    public boolean isSubscribed(String uri, String sessionId) {
        var subs = subscriptions.get(uri);
        return subs != null && subs.contains(sessionId);
    }

    /**
     * Subscribes the session to the resource URI. Add and removal both run inside the map's
     * per-key operation ({@code compute}/{@code computeIfPresent}), so a subscribe can never land
     * in a set that a concurrent unsubscribe has already unlinked from the map.
     */
    void subscribe(String uri, String sessionId) {
        subscriptions.compute(uri, (k, set) -> {
            if (set == null) {
                set = new CopyOnWriteArraySet<>();
            }
            set.add(sessionId);
            return set;
        });
    }

    /**
     * Removes the session's subscription to the URI, pruning the map entry when it empties.
     */
    void unsubscribe(String uri, String sessionId) {
        subscriptions.computeIfPresent(uri, (k, set) -> {
            set.remove(sessionId);
            return set.isEmpty() ? null : set;
        });
    }

    /**
     * Drops any subscription-set entry for a URI that no longer names a live resource, e.g. after
     * unregistering or renaming the resource that owned it. Called under {@link #writeLock} alongside
     * the index change; {@code subscriptions} is an independent map, so this never blocks subscribe.
     */
    private void dropSubscriptions(String uri) {
        subscriptions.remove(uri);
    }

    /**
     * Notifies all subscribed sessions that a resource has been updated.
     */
    @Override
    public void notifyResourceUpdated(String uri) {
        server.notifyResourceSubscriptions(uri);
        var subscribedSessionIds = subscriptions.get(uri);
        if (subscribedSessionIds == null || subscribedSessionIds.isEmpty()) {
            return;
        }
        var paramsMap = new LinkedHashMap<String, Object>();
        paramsMap.put("uri", uri);
        for (var sessionId : subscribedSessionIds) {
            server.getSession(sessionId)
                    .ifPresentOrElse(
                            session -> server.sendNotification(session, "notifications/resources/updated", paramsMap),
                            // Session is gone (closed/expired) — nothing ever sweeps its
                            // subscriptions, so drop it lazily here to stop the set growing
                            // with dead ids. Safe while iterating: COW set snapshot.
                            () -> unsubscribe(uri, sessionId));
        }
    }
}
