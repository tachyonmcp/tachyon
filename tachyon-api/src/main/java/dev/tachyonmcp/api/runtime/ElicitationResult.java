/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.runtime;

import dev.tachyonmcp.api.server.domain.Args;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The client's response to an elicitation request.
 *
 * @param action the user's action in response to the elicitation
 * @param content the submitted form data, or {@code null} when the client submitted no data
 */
public record ElicitationResult(Action action, @Nullable Args content) {

    /**
     * Creates an elicitation result.
     *
     * @param action the user's action
     * @param content the submitted form data, or {@code null} when absent
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public ElicitationResult {
        Objects.requireNonNull(action, "action");
    }

    /** {@return a builder for an elicitation result} */
    public static Builder builder() {
        return new DefaultBuilder();
    }

    /**
     * Creates an elicitation result.
     *
     * @param action the user's action
     * @param content the submitted form data, or {@code null} when absent
     * @return the result
     */
    public static ElicitationResult of(Action action, @Nullable Args content) {
        return new ElicitationResult(action, content);
    }

    /** Builds elicitation results. */
    public interface Builder {

        /**
         * Copies the values from an existing result.
         *
         * @param instance the result to copy
         * @return this builder
         */
        Builder from(ElicitationResult instance);

        /**
         * Sets the user's action.
         *
         * @param action the user's action
         * @return this builder
         */
        Builder action(Action action);

        /**
         * Sets the submitted form data.
         *
         * @param content the submitted data, or {@code null} when absent
         * @return this builder
         */
        Builder content(@Nullable Args content);

        /**
         * {@return a result with the configured action and optional content}
         *
         * @throws NullPointerException if the action was not set
         */
        ElicitationResult build();
    }

    /** The user action in response to an elicitation request. */
    public enum Action {
        /** The user submitted the form. */
        ACCEPT,
        /** The user explicitly declined the action. */
        DECLINE,
        /** The user dismissed the request without making an explicit choice. */
        CANCEL
    }

    private static final class DefaultBuilder implements Builder {
        private @Nullable Action action;
        private @Nullable Args content;

        @Override
        public Builder from(ElicitationResult instance) {
            action = instance.action();
            content = instance.content();
            return this;
        }

        @Override
        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        @Override
        public Builder content(@Nullable Args content) {
            this.content = content;
            return this;
        }

        @Override
        public ElicitationResult build() {
            return new ElicitationResult(action, content);
        }
    }
}
