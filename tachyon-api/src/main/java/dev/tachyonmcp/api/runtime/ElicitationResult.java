/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.runtime;

import dev.tachyonmcp.api.server.domain.Args;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** The client's response to an elicitation request. */
@Value.Immutable
@Value.Style(
        allParameters = true,
        visibility = Value.Style.ImplementationVisibility.PACKAGE,
        typeImmutable = "Default*")
public interface ElicitationResult {

    /** {@return the user's action in response to the elicitation} */
    Action action();

    /** {@return the submitted form data, or {@code null} when the client submitted no data} */
    @Nullable
    Args content();

    /** {@return a builder for an immutable elicitation result} */
    static Builder builder() {
        return DefaultElicitationResult.builder();
    }

    /**
     * Creates an immutable elicitation result.
     *
     * @param action the user's action
     * @param content the submitted form data, or {@code null} when absent
     * @return the result
     */
    static ElicitationResult of(Action action, @Nullable Args content) {
        return DefaultElicitationResult.of(action, content);
    }

    /** Builds immutable elicitation results. */
    interface Builder {

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

        /** {@return an immutable result with the configured action and optional content} */
        ElicitationResult build();
    }

    /** The user action in response to an elicitation request. */
    enum Action {
        /** The user submitted the form. */
        ACCEPT,
        /** The user explicitly declined the action. */
        DECLINE,
        /** The user dismissed the request without making an explicit choice. */
        CANCEL
    }
}
