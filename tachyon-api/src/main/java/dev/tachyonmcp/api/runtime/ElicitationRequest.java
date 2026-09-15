/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.runtime;

import dev.tachyonmcp.api.json.JsonSchema;
import org.immutables.value.Value;

/** A request to elicit additional information from the user via the client, in form mode. */
@Value.Immutable
@Value.Style(
        allParameters = true,
        visibility = Value.Style.ImplementationVisibility.PACKAGE,
        typeImmutable = "Default*")
public interface ElicitationRequest {

    /** {@return the message describing the information requested from the user} */
    String message();

    /** {@return the restricted JSON Schema describing the form's top-level primitive properties} */
    JsonSchema requestedSchema();

    /** {@return a builder for an immutable form request} */
    static Builder builder() {
        return DefaultElicitationRequest.builder();
    }

    /**
     * Creates an immutable form request.
     *
     * @param message the message presented to the user
     * @param requestedSchema the restricted JSON Schema describing the form
     * @return the request
     */
    static ElicitationRequest of(String message, JsonSchema requestedSchema) {
        return DefaultElicitationRequest.of(message, requestedSchema);
    }

    /** Builds immutable elicitation requests. */
    interface Builder {

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

        /** {@return an immutable request with the configured message and schema} */
        ElicitationRequest build();
    }
}
