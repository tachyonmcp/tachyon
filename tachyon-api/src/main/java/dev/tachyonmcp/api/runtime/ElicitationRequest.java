/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.runtime;

import dev.tachyonmcp.api.json.JsonSchema;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A request to elicit additional information from the user via the client, in form mode.
 *
 * @param message the message describing the information requested from the user
 * @param requestedSchema the restricted JSON Schema describing the form's top-level primitive properties
 */
public record ElicitationRequest(String message, JsonSchema requestedSchema) {

    /**
     * Creates a form request.
     *
     * @param message the message presented to the user
     * @param requestedSchema the restricted JSON Schema describing the form
     * @throws NullPointerException if any argument is {@code null}
     */
    public ElicitationRequest {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(requestedSchema, "requestedSchema");
    }

    /** {@return a builder for a form request} */
    public static Builder builder() {
        return new DefaultBuilder();
    }

    /**
     * Creates a form request.
     *
     * @param message the message presented to the user
     * @param requestedSchema the restricted JSON Schema describing the form
     * @return the request
     */
    public static ElicitationRequest of(String message, JsonSchema requestedSchema) {
        return new ElicitationRequest(message, requestedSchema);
    }

    /** Builds elicitation requests. */
    public interface Builder {

        /**
         * Copies the values from an existing request.
         *
         * @param instance the request to copy
         * @return this builder
         */
        Builder from(ElicitationRequest instance);

        /**
         * Sets the message presented to the user.
         *
         * @param message the request message
         * @return this builder
         */
        Builder message(String message);

        /**
         * Sets the restricted JSON Schema describing the form.
         *
         * @param requestedSchema the form schema
         * @return this builder
         */
        Builder requestedSchema(JsonSchema requestedSchema);

        /**
         * {@return a request with the configured message and schema}
         *
         * @throws NullPointerException if the message or schema was not set
         */
        ElicitationRequest build();
    }

    private static final class DefaultBuilder implements Builder {
        private @Nullable String message;
        private @Nullable JsonSchema requestedSchema;

        @Override
        public Builder from(ElicitationRequest instance) {
            message = instance.message();
            requestedSchema = instance.requestedSchema();
            return this;
        }

        @Override
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        @Override
        public Builder requestedSchema(JsonSchema requestedSchema) {
            this.requestedSchema = requestedSchema;
            return this;
        }

        @Override
        public ElicitationRequest build() {
            return new ElicitationRequest(message, requestedSchema);
        }
    }
}
