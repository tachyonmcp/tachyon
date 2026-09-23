/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.prompts;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.config.Mode;
import dev.tachyonmcp.api.server.features.prompts.AsyncPromptFn;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptFn;
import dev.tachyonmcp.api.server.features.prompts.Prompts;
import dev.tachyonmcp.core.server.config.FeatureConfig;
import dev.tachyonmcp.core.server.features.AbstractRegistry;
import dev.tachyonmcp.core.server.internal.HandlerFutures;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@InternalApi
public class DefaultPromptRegistry extends AbstractRegistry<PromptDescriptor, PromptEntry> implements PromptRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPromptRegistry.class);

    private final FeatureConfig config;

    /**
     * Creates a prompt registry with the given feature configuration.
     *
     * `@param` config the feature configuration governing registry behaviour and page size
     */
    public DefaultPromptRegistry(FeatureConfig config) {
        super(config.pageSize());
        this.config = config;
    }

    /**
     * Registers a prompt unless prompt support is disabled by the configured mode.
     *
     * @param descriptor the prompt descriptor to register
     * @param fn the prompt function
     * @return this registry
     */
    @Override
    public Prompts register(PromptDescriptor descriptor, PromptFn fn) {
        return registerAsync(descriptor, (context, request) -> {
            HandlerFutures.assumeVirtualThread();
            return HandlerFutures.completedOrFailed(() -> fn.apply(context, request));
        });
    }

    @Override
    public Prompts registerAsync(PromptDescriptor descriptor, AsyncPromptFn fn) {
        if (config.mode() == Mode.OFF) {
            logger.debug("Prompt '{}' not registered: prompts capability is OFF", descriptor.name());
            return this;
        }
        addItem(PromptEntry.of(descriptor, fn));
        return this;
    }

    /**
     * Removes the registered prompt with the specified name.
     *
     * @param name the name of the prompt to remove
     * @return {@code true} if a prompt was removed, {@code false} if no matching prompt was registered
     */
    @Override
    public boolean unregister(String name) {
        return removeItem(name);
    }

    /**
     * Finds a registered prompt descriptor by name.
     *
     * @param name the prompt name
     * @return the matching prompt descriptor, or an empty optional if no prompt is registered with that name
     */
    @Override
    public Optional<PromptDescriptor> find(String name) {
        var entry = get(name);
        return entry != null ? Optional.of(entry.descriptor()) : Optional.empty();
    }

    /**
     * Returns all registered prompt descriptors sorted by name.
     *
     * @return the registered prompt descriptors in ascending name order
     */
    @Override
    public List<PromptDescriptor> descriptors() {
        return getAll().stream()
                .map(PromptEntry::descriptor)
                .sorted(Comparator.comparing(PromptDescriptor::name))
                .toList();
    }
}
