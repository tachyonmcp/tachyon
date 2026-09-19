/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import dev.tachyonmcp.api.json.JsonSchema;
import java.util.Map;
import org.immutables.value.Value;

/** Requests user input via a form described by a JSON schema. */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface FormInputRequest extends InputRequest {

    /** Prompt message shown to the user. */
    String message();

    /** JSON schema describing the expected form fields. */
    JsonSchema requestedSchema();

    @Value.Check
    default void check() {
        if (message().isBlank()) throw new IllegalArgumentException("message must not be blank");
    }

    static Builder builder() {
        return DefaultFormInputRequest.builder();
    }

    @Deprecated
    static FormInputRequest of(String message, Map<String, Object> requestedSchema) {
        return DefaultFormInputRequest.builder()
                .message(message)
                .requestedSchema(requestedSchema)
                .build();
    }

    static FormInputRequest of(String message, JsonSchema requestedSchema) {
        return DefaultFormInputRequest.builder()
                .message(message)
                .requestedSchema(requestedSchema)
                .build();
    }

    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(FormInputRequest instance);

        Builder message(String message);

        /**
         * @deprecated use {@link #requestedSchema(JsonSchema)} instead
         */
        @Deprecated
        default Builder requestedSchema(Map<String, ?> entries) {
            return requestedSchema(JsonSchema.from(entries, Map.class));
        }

        Builder requestedSchema(JsonSchema jsonSchema);

        FormInputRequest build();
    }
}
