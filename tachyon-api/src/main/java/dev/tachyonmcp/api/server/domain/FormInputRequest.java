/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import dev.tachyonmcp.api.json.JsonSchema;
import java.util.Map;
import org.immutables.value.Value;

/** Requests user input via a form described by a JSON schema. */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface FormInputRequest extends InputRequest {

    /**
     * Prompt message shown to the user.
     *
     * @return the message
     */
    String message();

    /**
     * JSON schema describing the expected form fields.
     *
     * @return the requested schema
     */
    JsonSchema requestedSchema();

    /**
     * Validates the value invariants.
     */
    @Value.Check
    default void check() {
        if (message().isBlank()) throw new IllegalArgumentException("message must not be blank");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultFormInputRequest.builder();
    }

    /**
     * Creates a {@link FormInputRequest} from the supplied values.
     *
     * @param message the message
     * @param requestedSchema the requested schema
     * @return the form input request
     */
    @Deprecated
    static FormInputRequest of(String message, Map<String, Object> requestedSchema) {
        return DefaultFormInputRequest.builder()
                .message(message)
                .requestedSchema(requestedSchema)
                .build();
    }

    /**
     * Creates a {@link FormInputRequest} from the supplied values.
     *
     * @param message the message
     * @param requestedSchema the requested schema
     * @return the form input request
     */
    static FormInputRequest of(String message, JsonSchema requestedSchema) {
        return DefaultFormInputRequest.builder()
                .message(message)
                .requestedSchema(requestedSchema)
                .build();
    }

    /**
     * Builder for the enclosing type.
     */
    interface Builder {
        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(FormInputRequest instance);

        /**
         * Sets the message.
         *
         * @param message the message
         * @return this builder
         */
        Builder message(String message);

        /**
         * @deprecated use {@link #requestedSchema(JsonSchema)} instead
         * @param entries the entries
         * @return this builder
         */
        @Deprecated
        default Builder requestedSchema(Map<String, ?> entries) {
            return requestedSchema(JsonSchema.from(entries, Map.class));
        }

        /**
         * Sets the requested schema.
         *
         * @param jsonSchema the json schema
         * @return this builder
         */
        Builder requestedSchema(JsonSchema jsonSchema);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        FormInputRequest build();
    }
}
