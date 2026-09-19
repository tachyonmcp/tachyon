/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tools;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.HasMeta;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.InputRequestBundle;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** Outcome of a tool invocation: success, error, input-required. */
public sealed interface ToolResult extends HasMeta
        permits ToolResult.Error, ToolResult.InputRequired, ToolResult.Success, ToolResult.Task {

    @Override
    default @Nullable Map<String, Object> meta() {
        return null;
    }

    /**
     * Returns a copy of this result with {@code m} merged into its {@code _meta} entries; returns
     * this unchanged when {@code m} is empty.
     *
     * @param m the {@code _meta} entries to merge in
     * @return a result carrying the merged metadata
     */
    ToolResult withMeta(Map<String, Object> m);

    /**
     * Returns a copy of this result with a single {@code _meta} entry set.
     *
     * @param key   the {@code _meta} key
     * @param value the {@code _meta} value
     * @return a result carrying the added metadata entry
     */
    default ToolResult withMeta(String key, Object value) {
        return withMeta(Map.of(key, value));
    }

    /**
     * A successful invocation, carrying an optional structured value and its content blocks.
     */
    @Value.Immutable
    @Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*", with = "", from = "")
    non-sealed interface Success extends ToolResult {

        /**
         * Returns the structured payload, or {@code null} when none was set.
         *
         * @return the structured payload, or {@code null}
         */
        @Nullable
        Object structuredValue();

        /**
         * Returns the content blocks, possibly empty.
         *
         * @return the content blocks
         */
        List<ContentBlock> content();

        @Override
        @Nullable
        Map<String, Object> meta();

        /** Returns the structured value, or empty when none was set. */
        default Optional<Object> structured() {
            return Optional.ofNullable(structuredValue());
        }

        @Override
        default Success withMeta(Map<String, Object> m) {
            if (m.isEmpty()) return this;
            return builder()
                    .structuredValue(structuredValue())
                    .content(content())
                    .meta(HasMeta.merge(meta(), m))
                    .build();
        }

        /**
         * Creates a new builder for constructing {@code Success} instances.
         *
         * @return a new builder
         */
        static Builder builder() {
            return DefaultSuccess.builder();
        }

        /**
         * Creates a successful result with no {@code _meta}.
         *
         * @param structuredValue the structured payload, or {@code null} when none was set
         * @param content         the content blocks, possibly empty
         * @return a new successful result
         */
        static Success of(@Nullable Object structuredValue, List<ContentBlock> content) {
            return builder().structuredValue(structuredValue).content(content).build();
        }

        /**
         * Builder for {@link Success}.
         */
        interface Builder {
            /**
             * Sets the structured payload.
             *
             * @param structuredValue the structured payload, or {@code null}
             * @return this builder
             */
            Builder structuredValue(@Nullable Object structuredValue);

            /**
             * Sets the content blocks.
             *
             * @param content the content blocks
             * @return this builder
             */
            Builder content(Iterable<? extends ContentBlock> content);

            /**
             * Sets the content blocks.
             *
             * @param content the content blocks
             * @return this builder
             */
            default Builder content(ContentBlock... content) {
                return content(List.of(content));
            }

            /**
             * Sets the metadata entries.
             *
             * @param meta the metadata map, or {@code null}
             * @return this builder
             */
            Builder meta(@Nullable Map<String, ?> meta);

            /**
             * Builds the {@code Success} instance.
             *
             * @return a new successful result
             */
            Success build();
        }
    }

    /**
     * A failed invocation carrying the error's content blocks.
     */
    @Value.Immutable
    @Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*", with = "", from = "")
    non-sealed interface Error extends ToolResult {

        /**
         * Returns the error content blocks.
         *
         * @return the error content blocks
         */
        List<ContentBlock> content();

        @Override
        @Nullable
        Map<String, Object> meta();

        /**
         * Creates a new builder for constructing {@code Error} instances.
         *
         * @return a new builder
         */
        static Builder builder() {
            return DefaultError.builder();
        }

        /**
         * Creates a failed result carrying the given content blocks.
         *
         * @param content the error content blocks
         * @return a new error
         */
        static Error of(List<? extends ContentBlock> content) {
            return DefaultError.builder().content(content).build();
        }

        @Override
        default Error withMeta(Map<String, Object> m) {
            if (m.isEmpty()) return this;
            return builder().content(content()).meta(HasMeta.merge(meta(), m)).build();
        }

        /**
         * Builder for {@link Error}.
         */
        interface Builder {
            /**
             * Sets the error content blocks.
             *
             * @param content the error content blocks
             * @return this builder
             */
            Builder content(Iterable<? extends ContentBlock> content);

            /**
             * Sets the error content blocks.
             *
             * @param content the error content blocks
             * @return this builder
             */
            default Builder content(ContentBlock... content) {
                return content(List.of(content));
            }

            /**
             * Sets the metadata entries.
             *
             * @param meta the metadata map, or {@code null}
             * @return this builder
             */
            Builder meta(@Nullable Map<String, ?> meta);

            /**
             * Builds the {@code Error} instance.
             *
             * @return a new error
             */
            Error build();
        }
    }

    /**
     * Signals that completing the tool call requires additional input from the caller.
     */
    @Value.Immutable
    @Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*", with = "", from = "")
    non-sealed interface InputRequired extends ToolResult, dev.tachyonmcp.api.server.domain.InputRequired {

        @Override
        @Nullable
        Map<String, Object> meta();

        /**
         * Creates a new builder for constructing {@code InputRequired} instances.
         *
         * @return a new builder
         */
        static Builder builder() {
            return DefaultInputRequired.builder();
        }

        /**
         * Creates an input-required result with no {@code _meta}.
         *
         * @param request the requested inputs and opaque state to echo back
         * @return a new input-required result
         */
        static InputRequired of(InputRequestBundle request) {
            return builder().request(request).build();
        }

        @Override
        default InputRequired withMeta(Map<String, Object> m) {
            if (m.isEmpty()) return this;
            return builder().request(request()).meta(HasMeta.merge(meta(), m)).build();
        }

        /**
         * Builder for {@link InputRequired}.
         */
        interface Builder {
            /**
             * Sets the requested inputs and opaque state to echo back.
             *
             * @param request the input request bundle
             * @return this builder
             */
            Builder request(InputRequestBundle request);

            /**
             * Sets the metadata entries.
             *
             * @param meta the metadata map, or {@code null}
             * @return this builder
             */
            Builder meta(@Nullable Map<String, ?> meta);

            /**
             * Builds the {@code InputRequired} instance.
             *
             * @return a new input-required result
             */
            InputRequired build();
        }
    }

    /** A task projection created by this tool invocation. */
    @Value.Immutable
    @Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*", with = "", from = "")
    @ExperimentalApi
    non-sealed interface Task extends ToolResult {

        /** Returns the initial task projection. */
        TaskSnapshot snapshot();

        @Override
        default @Nullable Map<String, Object> meta() {
            return snapshot().meta();
        }

        @Override
        default Task withMeta(Map<String, Object> m) {
            if (m.isEmpty()) return this;
            var snapshot = TaskSnapshot.builder()
                    .from(snapshot())
                    .meta(HasMeta.merge(snapshot().meta(), m))
                    .build();
            return of(snapshot);
        }

        /** Creates a task result for the supplied initial projection. */
        @ExperimentalApi
        static Task of(TaskSnapshot snapshot) {
            return DefaultTask.builder().snapshot(snapshot).build();
        }

        /** Builder for {@link Task}. */
        @ExperimentalApi
        interface Builder {
            /** Sets the initial task projection. */
            Builder snapshot(TaskSnapshot snapshot);

            /** Builds the task result. */
            Task build();
        }
    }

    /**
     * Creates a successful result containing a single text content block.
     *
     * @param t the text
     * @return a successful tool result
     */
    static ToolResult text(String t) {
        return Success.of(null, List.of(TextContent.of(t)));
    }

    /**
     * Creates a successful result containing the given content blocks.
     *
     * @param blocks the content blocks
     * @return a successful tool result
     */
    static ToolResult content(ContentBlock... blocks) {
        return Success.of(null, List.of(blocks));
    }

    /**
     * Creates a success result carrying {@code payload} as structured content plus an explicit
     * human-readable text block.
     *
     * @param payload the structured payload
     * @param text    the text content for the content block
     * @return a successful tool result
     */
    static <T> ToolResult structured(T payload, String text) {
        return Success.of(payload, List.of(TextContent.of(text)));
    }

    /**
     * Creates a success result carrying {@code payload} as structured content with no text block.
     *
     * <p>The server emits the serialized JSON of {@code payload} as the text content at encode
     * time (MCP backwards-compat for structured results). Use {@link #structured(Object, String)}
     * to supply an explicit human-readable text block instead.
     *
     * @param payload the structured payload
     * @return a successful tool result
     */
    static <T> ToolResult structured(T payload) {
        return Success.of(payload, List.of());
    }

    /**
     * Creates a failed result with a single text content block carrying the given error message.
     *
     * @param message the error message
     * @return a failed tool result
     */
    static ToolResult error(String message) {
        return Error.of(List.of(TextContent.of(message)));
    }

    /**
     * Creates a failed result carrying the given content blocks.
     *
     * @param blocks the error content blocks
     * @return a failed tool result
     */
    static ToolResult error(ContentBlock... blocks) {
        return Error.of(List.of(blocks));
    }

    /** Creates a successful result with no structured value and no content. */
    static ToolResult empty() {
        return Success.of(null, List.of());
    }

    /** Creates a task result carrying its initial immutable projection. */
    @ExperimentalApi
    static ToolResult task(TaskSnapshot snapshot) {
        return Task.of(snapshot);
    }

    /**
     * Creates a success result with a pre-serialized JSON payload. The bytes skip the Jackson
     * value-to-tree conversion of ordinary structured values and are parsed once at envelope
     * encoding (plus once more when output schema validation is active).
     *
     * @param json a pre-serialized JSON object string
     * @param text the text content for the content block
     */
    static ToolResult raw(String json, String text) {
        return Success.of(JsonDocument.of(json), List.of(TextContent.of(text)));
    }

    /**
     * Creates a result that requests additional input from the caller before the tool call can
     * complete.
     *
     * @param reqs  the requested inputs, keyed by request id
     * @param state an opaque state token to echo back with the caller's response, or {@code null}
     * @return a result signaling that input is required
     */
    static ToolResult inputRequired(Map<String, ? extends InputRequest> reqs, @Nullable String state) {
        return ToolResult.InputRequired.of(new InputRequestBundle(reqs, state));
    }
}
