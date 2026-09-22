/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.config.RuntimeConfig;
import dev.tachyonmcp.api.server.features.completions.Completions;
import dev.tachyonmcp.api.server.features.prompts.Prompts;
import dev.tachyonmcp.api.server.features.resources.Resources;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import dev.tachyonmcp.api.server.features.tools.Tools;
import java.util.concurrent.Executor;

/**
 * Server surface available while bootstrapping an extension. Experimental — the shape may change.
 */
@ExperimentalApi
public interface ExtensionContext {

    /** Returns the tool registry façade. */
    Tools tools();

    /** Returns the resource registry façade. */
    Resources resources();

    /** Returns the prompt registry façade. */
    Prompts prompts();

    /** Returns the completion registry façade. */
    Completions completions();

    /** Returns the task runtime façade. */
    Tasks tasks();

    /** Returns the handler executor. */
    Executor executor();

    /** Returns handler runtime settings. */
    RuntimeConfig runtime();

    /**
     * Registers a raw JSON-RPC method handler owned by this extension. Call from
     * {@link ServerExtension#bootstrap(ExtensionContext)}; the method is routed to this extension
     * and gated by its {@link ServerExtension#negotiation()} policy.
     *
     * @param method the JSON-RPC method name to dispatch to the handler
     * @param handler the transport-neutral handler
     */
    void registerHandler(String method, ExtensionMethodHandler handler);
}
