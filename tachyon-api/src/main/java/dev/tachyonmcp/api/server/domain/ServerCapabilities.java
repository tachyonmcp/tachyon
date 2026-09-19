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

    /** Prompt capabilities ({@code null} = not supported). */
    @Nullable
    Prompts prompts();

    /** Resource capabilities ({@code null} = not supported). */
    @Nullable
    Resources resources();

    /** Tool capabilities ({@code null} = not supported). */
    @Nullable
    Tools tools();

    /** Whether logging is supported. */
    boolean logging();

    /** Whether completion is supported. */
    boolean completions();

    /** Task capabilities ({@code null} = not supported). */
    @Nullable
    Tasks tasks();

    /** Experimental capability extensions. */
    @Nullable
    JsonObject experimental();

    static Builder builder() {
        return DefaultServerCapabilities.builder();
    }

    /**
     * Builder for {@link ServerCapabilities}.
     */
    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ServerCapabilities instance);

        /** Prompt capabilities ({@code null} = not supported). */
        Builder prompts(@Nullable Prompts prompts);

        /** Resource capabilities ({@code null} = not supported). */
        Builder resources(@Nullable Resources resources);

        /** Tool capabilities ({@code null} = not supported). */
        Builder tools(@Nullable Tools tools);

        /** Whether logging is supported. */
        Builder logging(boolean logging);

        /** Whether completion is supported. */
        Builder completions(boolean completions);

        /** Task capabilities ({@code null} = not supported). */
        Builder tasks(@Nullable Tasks tasks);

        /** Experimental capability extensions. */
        Builder experimental(@Nullable JsonObject experimental);

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
