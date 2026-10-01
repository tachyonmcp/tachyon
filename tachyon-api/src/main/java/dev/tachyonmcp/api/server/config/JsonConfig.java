/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.config;

import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerde;
import dev.tachyonmcp.api.json.PayloadSerializer;
import org.jspecify.annotations.Nullable;

/**
 * JSON payload configuration for the server: serializer, deserializer, schema validators, and
 * schema parsing factory.
 *
 * @param serializer      payload serializer, or {@code null} to keep the server default
 * @param deserializer    payload deserializer, or {@code null} to keep the server default
 * @param inputValidator  input schema validator, or {@code null} to keep the server default
 * @param outputValidator output schema validator, or {@code null} to keep the server default
 * @author Konstantin Pavlov
 */
public record JsonConfig(
        @Nullable PayloadSerializer serializer,
        @Nullable PayloadDeserializer deserializer,
        @Nullable JsonSchemaValidator inputValidator,
        @Nullable JsonSchemaValidator outputValidator) {

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link JsonConfig}.
     */
    public static final class Builder {
        private @Nullable PayloadSerializer serializer;
        private @Nullable PayloadDeserializer deserializer;
        private @Nullable JsonSchemaValidator inputValidator;
        private @Nullable JsonSchemaValidator outputValidator;

        private Builder() {}

        /**
         * Sets both serializer and deserializer from a combined serde.
         *
         * @param serde the payload serializer and deserializer
         * @return this builder
         */
        public Builder serde(PayloadSerde serde) {
            return serializer(serde).deserializer(serde);
        }

        /**
         * Sets the serializer.
         *
         * @param serializer the payload serializer
         * @return this builder
         */
        public Builder serializer(@Nullable PayloadSerializer serializer) {
            this.serializer = serializer;
            return this;
        }

        /**
         * Sets the deserializer.
         *
         * @param deserializer the payload deserializer
         * @return this builder
         */
        public Builder deserializer(@Nullable PayloadDeserializer deserializer) {
            this.deserializer = deserializer;
            return this;
        }

        /**
         * Sets the schema validator.
         *
         * @param validator the validator
         * @return this builder
         */
        public Builder schemaValidator(@Nullable JsonSchemaValidator validator) {
            return inputSchemaValidator(validator).outputSchemaValidator(validator);
        }

        /**
         * Sets the input schema validator.
         *
         * @param inputValidator the input validator
         * @return this builder
         */
        public Builder inputSchemaValidator(@Nullable JsonSchemaValidator inputValidator) {
            this.inputValidator = inputValidator;
            return this;
        }

        /**
         * Sets the output schema validator.
         *
         * @param outputValidator the output validator
         * @return this builder
         */
        public Builder outputSchemaValidator(@Nullable JsonSchemaValidator outputValidator) {
            this.outputValidator = outputValidator;
            return this;
        }

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public JsonConfig build() {
            return new JsonConfig(serializer, deserializer, inputValidator, outputValidator);
        }
    }
}
