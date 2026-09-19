/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tools;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * An incoming tool call request with the tool name, arguments, and metadata.
 */
@Value.Immutable
@Value.Style(
        allParameters = true,
        visibility = Value.Style.ImplementationVisibility.PACKAGE,
        typeImmutable = "Default*")
public interface ToolRequest extends ServerFeature.Request {

    /**
     * Returns the tool name.
     *
     * @return the tool name
     */
    String name();

    /**
     * Validates that the tool name is not blank.
     *
     * @throws IllegalArgumentException if {@code name} is blank
     */
    @Value.Check
    default void check() {
        if (name().isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    /**
     * Returns the arguments for this tool request.
     *
     * @return the tool arguments, or empty if none provided
     */
    @Value.Default
    default Args arguments() {
        return Args.empty();
    }

    @Nullable
    @Override
    Map<String, Object> meta();

    /**
     * Returns the payload deserializer configured for this request, or {@code null} if not set.
     *
     * <p>Superseded by {@link #arguments()}, which already carries the deserializer; this direct
     * accessor may be removed once callers migrate off it.
     *
     * @return the payload deserializer, or {@code null}
     */
    @ExperimentalApi
    @Nullable
    PayloadDeserializer payloadDeserializer();

    /**
     * The client's {@code _meta.progressToken} from this request, or {@code null} if the client
     * did not opt into progress notifications for this call.
     *
     * @return the progress token, or {@code null}
     */
    @Nullable
    ProgressToken progressToken();

    /**
     * Returns the input response map from interactive tool calls, or {@code null}.
     *
     * @return the input responses, or {@code null}
     */
    @Nullable
    Map<String, Object> inputResponses();

    /**
     * Returns the request state string, or {@code null}.
     *
     * @return the request state, or {@code null}
     */
    @Nullable
    String requestState();

    /**
     * Creates a new builder for constructing {@code ToolRequest} instances.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultToolRequest.builder();
    }

    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ToolRequest instance);

        /**
         * Sets the tool name.
         *
         * @param name the tool name
         * @return this builder
         */
        Builder name(String name);

        /**
         * Sets the tool arguments.
         *
         * @param arguments the tool arguments
         * @return this builder
         */
        Builder arguments(Args arguments);

        /**
         * Sets the metadata entries.
         *
         * @param entries the metadata map, or {@code null}
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Sets the payload deserializer.
         *
         * @param deserializer the deserializer, or {@code null}
         * @return this builder
         */
        @ExperimentalApi
        Builder payloadDeserializer(@Nullable PayloadDeserializer deserializer);

        /**
         * Sets the progress token.
         *
         * @param progressToken the progress token, or {@code null} to disable progress notifications
         * @return this builder
         */
        Builder progressToken(@Nullable ProgressToken progressToken);

        //        Builder cancellation(@Nullable Cancellation cancellation);

        /**
         * Sets the input responses for interactive tool calls.
         *
         * @param inputResponses the input responses, or {@code null}
         * @return this builder
         */
        Builder inputResponses(@Nullable Map<String, ?> inputResponses);

        /**
         * Sets the request state.
         *
         * @param requestState the request state, or {@code null}
         * @return this builder
         */
        Builder requestState(@Nullable String requestState);

        /**
         * Builds the {@code ToolRequest} instance.
         *
         * @return a new tool request
         */
        ToolRequest build();
    }
}
