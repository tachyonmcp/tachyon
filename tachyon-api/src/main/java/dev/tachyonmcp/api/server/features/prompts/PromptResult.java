/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.prompts;

import dev.tachyonmcp.api.server.domain.HasMeta;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.InputRequestBundle;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import java.util.List;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Result of a prompt function invocation.
 * <p>
 * A function returns either a list of messages or a request for additional input.
 */
public sealed interface PromptResult extends HasMeta permits PromptResult.Messages, PromptResult.InputRequired {

    @Override
    default @Nullable Map<String, Object> meta() {
        return null;
    }

    /**
     * Returns this result with the supplied metadata merged into its {@code _meta} entries.
     *
     * @param entries metadata entries to merge
     * @return a result carrying the merged metadata
     */
    PromptResult withMeta(Map<String, Object> entries);

    /**
     * Returns this result with one metadata entry.
     *
     * @param key metadata key
     * @param value metadata value
     * @return a result carrying the metadata
     */
    default PromptResult withMeta(String key, Object value) {
        return withMeta(Map.of(key, value));
    }

    /**
     * A prompt result containing one or more prompt messages.
     */
    @Value.Immutable
    @Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*", with = "", from = "")
    non-sealed interface Messages extends PromptResult {

        /**
         * Returns the prompt messages.
         *
         * @return the prompt messages, or an empty list
         */
        List<PromptMessage> messages();

        @Override
        @Nullable
        Map<String, Object> meta();

        @Override
        default Messages withMeta(Map<String, Object> m) {
            if (m.isEmpty()) return this;
            return builder().messages(messages()).meta(HasMeta.merge(meta(), m)).build();
        }

        /**
         * Creates a new builder for constructing {@code Messages} instances.
         *
         * @return a new builder
         */
        static Builder builder() {
            return DefaultMessages.builder();
        }

        /**
         * Creates a message result with no {@code _meta}.
         *
         * @param messages the prompt messages
         * @return a new message result
         */
        static Messages of(List<PromptMessage> messages) {
            return builder().messages(messages).build();
        }

        /**
         * Builder for {@link Messages}.
         */
        interface Builder {
            /**
             * Sets the prompt messages.
             *
             * @param messages the prompt messages
             * @return this builder
             */
            Builder messages(Iterable<? extends PromptMessage> messages);

            /**
             * Sets the prompt messages.
             *
             * @param messages the prompt messages
             * @return this builder
             */
            default Builder messages(PromptMessage... messages) {
                return messages(List.of(messages));
            }

            /**
             * Sets the metadata entries.
             *
             * @param meta the metadata map, or {@code null}
             * @return this builder
             */
            Builder meta(@Nullable Map<String, ?> meta);

            /**
             * Builds the {@code Messages} instance.
             *
             * @return a new message result
             */
            Messages build();
        }
    }

    /**
     * A prompt result that requests additional input from the client.
     */
    @Value.Immutable
    @Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*", with = "", from = "")
    non-sealed interface InputRequired extends PromptResult, dev.tachyonmcp.api.server.domain.InputRequired {

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

    /**
     * Creates a prompt result with the given messages.
     *
     * @param messages the prompt messages
     * @return the prompt result
     */
    static PromptResult messages(List<PromptMessage> messages) {
        return Messages.of(messages);
    }

    /**
     * Creates a prompt result that requests additional input.
     *
     * @param inputRequests the input requests keyed by field name
     * @param requestState  optional opaque state for resumption
     * @return the prompt result
     */
    static PromptResult inputRequired(
            Map<String, ? extends InputRequest> inputRequests, @Nullable String requestState) {
        return InputRequired.of(new InputRequestBundle(inputRequests, requestState));
    }
}
