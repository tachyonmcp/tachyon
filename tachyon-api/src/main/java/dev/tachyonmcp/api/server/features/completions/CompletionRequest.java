/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.completions;

import dev.tachyonmcp.api.server.ServerFeature;
import java.util.Map;
import java.util.Objects;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * A {@code completion/complete} request: the argument being completed and any
 * previously-resolved sibling arguments supplied as context.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface CompletionRequest extends ServerFeature.Request {

    /**
     * The name of the argument being completed.
     */
    String argumentName();

    /**
     * The current (partial) value typed for the argument.
     */
    String argumentValue();

    @Nullable
    @Override
    Map<String, Object> meta();

    /**
     * Previously-resolved argument name/value pairs, or empty if none.
     *
     * @return the resolved arguments map
     */
    @Value.Default
    default Map<String, String> resolvedArguments() {
        return Map.of();
    }

    @Value.Check
    default void check() {
        if (argumentName().isBlank()) throw new IllegalArgumentException("argumentName must not be blank");
        Objects.requireNonNull(argumentValue(), "argumentValue must not be null");
    }

    /**
     * Creates a completion request with the given argument name, partial value, and resolved siblings.
     *
     * @param argumentName     the name of the argument being completed
     * @param argumentValue    the current (partial) value typed for the argument
     * @param resolvedArguments previously-resolved argument name/value pairs
     * @return a new completion request
     */
    static CompletionRequest of(String argumentName, String argumentValue, Map<String, String> resolvedArguments) {
        return DefaultCompletionRequest.builder()
                .argumentName(argumentName)
                .argumentValue(argumentValue)
                .resolvedArguments(resolvedArguments)
                .build();
    }

    /**
     * Creates a new builder for {@link CompletionRequest}.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultCompletionRequest.builder();
    }

    interface Builder {
        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy from
         * @return this builder
         */
        Builder from(CompletionRequest instance);

        /**
         * Sets the name of the argument being completed.
         *
         * @param argumentName the argument name
         * @return this builder
         */
        Builder argumentName(String argumentName);

        /**
         * Sets the current (partial) value typed for the argument.
         *
         * @param argumentValue the partial value
         * @return this builder
         */
        Builder argumentValue(String argumentValue);

        /**
         * Sets the previously-resolved argument name/value pairs.
         *
         * @param resolvedArguments the resolved arguments map
         * @return this builder
         */
        Builder resolvedArguments(Map<String, ? extends String> resolvedArguments);

        /**
         * Sets optional protocol extension metadata.
         *
         * @param entries metadata entries, or {@code null} for none
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Builds the {@link CompletionRequest}.
         *
         * @return a new completion request
         */
        CompletionRequest build();
    }
}
