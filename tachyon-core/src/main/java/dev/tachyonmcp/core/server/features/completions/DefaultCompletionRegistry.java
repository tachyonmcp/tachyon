/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.completions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.config.Mode;
import dev.tachyonmcp.api.server.features.completions.AsyncCompletionFn;
import dev.tachyonmcp.api.server.features.completions.CompletionFn;
import dev.tachyonmcp.api.server.features.completions.Completions;
import dev.tachyonmcp.core.server.internal.HandlerFutures;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of completion handlers, keyed independently by prompt name and by resource
 * URI/template.
 */
@InternalApi
public class DefaultCompletionRegistry implements CompletionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DefaultCompletionRegistry.class);

    private final ConcurrentHashMap<String, AsyncCompletionFn> promptFns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AsyncCompletionFn> resourceFns = new ConcurrentHashMap<>();
    private final Mode mode;

    public DefaultCompletionRegistry() {
        this(Mode.AUTO);
    }

    public DefaultCompletionRegistry(Mode mode) {
        this.mode = mode;
    }

    @Override
    public Completions registerForPrompt(String promptName, CompletionFn fn) {
        return registerForPromptAsync(promptName, adapt(fn));
    }

    @Override
    public Completions registerForPromptAsync(String promptName, AsyncCompletionFn fn) {
        if (mode == Mode.OFF) {
            logger.debug("Completion '{}' not registered: completions capability is OFF", promptName);
            return this;
        }
        promptFns.put(promptName, fn);

        return this;
    }

    @Override
    public Completions registerForResource(String uriOrTemplate, CompletionFn fn) {
        return registerForResourceAsync(uriOrTemplate, adapt(fn));
    }

    @Override
    public Completions registerForResourceAsync(String uriOrTemplate, AsyncCompletionFn fn) {
        if (mode == Mode.OFF) {
            logger.debug("Completion for '{}' not registered: completions capability is OFF", uriOrTemplate);
            return this;
        }
        resourceFns.put(uriOrTemplate, fn);
        return this;
    }

    @Override
    public boolean registerForPromptIfAbsent(String promptName, CompletionFn fn) {
        if (mode == Mode.OFF) {
            logger.debug("Completion '{}' not registered: completions capability is OFF", promptName);
            return false;
        }
        if (promptFns.putIfAbsent(promptName, adapt(fn)) == null) return true;
        logger.debug("Derived completion for prompt '{}' skipped: a handler is already registered", promptName);
        return false;
    }

    @Override
    public boolean registerForResourceIfAbsent(String uriOrTemplate, CompletionFn fn) {
        if (mode == Mode.OFF) {
            logger.debug("Completion for '{}' not registered: completions capability is OFF", uriOrTemplate);
            return false;
        }
        if (resourceFns.putIfAbsent(uriOrTemplate, adapt(fn)) == null) return true;
        logger.debug("Derived completion for resource '{}' skipped: a handler is already registered", uriOrTemplate);
        return false;
    }

    private static AsyncCompletionFn adapt(CompletionFn fn) {
        return (context, request) -> {
            HandlerFutures.assumeVirtualThread();
            return HandlerFutures.completedOrFailed(() -> fn.apply(context, request));
        };
    }

    @Override
    public boolean unregisterForPrompt(String promptName) {
        return promptFns.remove(promptName) != null;
    }

    @Override
    public boolean unregisterForResource(String uriOrTemplate) {
        return resourceFns.remove(uriOrTemplate) != null;
    }

    Optional<AsyncCompletionFn> findForPrompt(String promptName) {
        return Optional.ofNullable(promptFns.get(promptName));
    }

    Optional<AsyncCompletionFn> findForResource(String uriOrTemplate) {
        return Optional.ofNullable(resourceFns.get(uriOrTemplate));
    }

    /**
     * Returns whether no completion handlers are registered.
     */
    @Override
    public boolean isEmpty() {
        return promptFns.isEmpty() && resourceFns.isEmpty();
    }
}
