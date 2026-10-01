/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import dev.tachyonmcp.api.json.JsonObject;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Capabilities the server advertises to the client during initialization.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface ServerCapabilities {

    /**
     * Prompt capabilities ({@code null} = not supported).
     *
     * @return the prompts
     */
    @Nullable
    Prompts prompts();

    /**
     * Resource capabilities ({@code null} = not supported).
     *
     * @return the resources
     */
    @Nullable
    Resources resources();

    /**
     * Tool capabilities ({@code null} = not supported).
     *
     * @return the tools
     */
    @Nullable
    Tools tools();

    /**
     * Whether logging is supported.
     *
     * @return the logging
     */
    boolean logging();

    /**
     * Whether completion is supported.
     *
     * @return the completions
     */
    boolean completions();

    /**
     * Task capabilities ({@code null} = not supported).
     *
     * @return the tasks
     */
    @Nullable
    Tasks tasks();

    /**
     * Experimental capability extensions.
     *
     * @return the experimental
     */
    @Nullable
    JsonObject experimental();

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultServerCapabilities.builder();
    }

    /**
     * Builder for {@link ServerCapabilities}.
     */
    interface Builder {
        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(ServerCapabilities instance);

        /**
         * Prompt capabilities ({@code null} = not supported).
         *
         * @param prompts the prompts
         * @return this builder
         */
        Builder prompts(@Nullable Prompts prompts);

        /**
         * Resource capabilities ({@code null} = not supported).
         *
         * @param resources the resources
         * @return this builder
         */
        Builder resources(@Nullable Resources resources);

        /**
         * Tool capabilities ({@code null} = not supported).
         *
         * @param tools the tools
         * @return this builder
         */
        Builder tools(@Nullable Tools tools);

        /**
         * Whether logging is supported.
         *
         * @param logging the logging
         * @return this builder
         */
        Builder logging(boolean logging);

        /**
         * Whether completion is supported.
         *
         * @param completions the completions
         * @return this builder
         */
        Builder completions(boolean completions);

        /**
         * Task capabilities ({@code null} = not supported).
         *
         * @param tasks the tasks
         * @return this builder
         */
        Builder tasks(@Nullable Tasks tasks);

        /**
         * Experimental capability extensions.
         *
         * @param experimental the experimental
         * @return this builder
         */
        Builder experimental(@Nullable JsonObject experimental);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        ServerCapabilities build();
    }

    /**
     * Prompt capabilities.
     *
     * @param listChanged whether the server emits prompt list change notifications
     */
    record Prompts(boolean listChanged) {}

    /**
     * Tool capabilities.
     *
     * @param listChanged whether the server emits tool list change notifications
     */
    record Tools(boolean listChanged) {}

    /**
     * Resource capabilities.
     *
     * @param subscribe   whether the server supports resource subscriptions
     * @param listChanged whether the server emits resource list change notifications
     */
    record Resources(boolean subscribe, boolean listChanged) {}

    /**
     * Server task capabilities
     *
     * @param list             Server supports the `tasks/list` operation
     * @param cancel           Server supports the `tasks/cancel` operation
     * @param toolCallRequests Server supports task-augmented `tools/call` requests
     */
    record Tasks(boolean list, boolean cancel, boolean toolCallRequests) {}
}
