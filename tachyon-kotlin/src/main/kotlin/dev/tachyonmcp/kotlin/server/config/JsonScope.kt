// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.json.JsonSchemaValidator
import dev.tachyonmcp.api.json.PayloadDeserializer
import dev.tachyonmcp.api.json.PayloadSerde
import dev.tachyonmcp.api.json.PayloadSerializer
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory
import dev.tachyonmcp.core.server.ServerBuilder
import dev.tachyonmcp.kotlin.server.TachyonDsl

/**
 * Configures the JSON payload boundary: payload serde, schema factory, and validators.
 * Mirrors the `dev.tachyonmcp.core.server.json` package.
 *
 * Assign [dev.tachyonmcp.api.json.JsonSchemaValidator.noop] to skip validation for a direction.
 *
 * @author Konstantin Pavlov
 */
@TachyonDsl
public class JsonScope
    internal constructor() {
        /**
         * Payload serializer/deserializer for structured values and arguments.
         * `null` keeps Tachyon's Jackson default.
         * Sets both [serializer] and [deserializer] when assigned.
         */
        public var serde: PayloadSerde? = null

        /** Payload serializer, or `null` to keep the server default. */
        public var serializer: PayloadSerializer? = null

        /** Payload deserializer, or `null` to keep the server default. */
        public var deserializer: PayloadDeserializer? = null

        /** Input schema validator, or `null` to keep the server default. */
        public var inputValidator: JsonSchemaValidator? = null

        /** Output schema validator, or `null` to keep the server default. */
        public var outputValidator: JsonSchemaValidator? = null

        /**
         * Parses/validates encoded tool schemas; must handle `String` sources. `null` keeps the
         * server default (`Jackson3JsonFactory`). Unrelated to `typedTool`'s schema generation,
         * which always resolves through the [dev.tachyonmcp.api.json.spi.JsonSchemaFactory]
         * `META-INF/services` chain — see `TachyonServerBuilder.typedTool`.
         */
        public var schemaFactory: JsonSchemaFactory<*>? = null

        internal fun applyTo(builder: ServerBuilder) {
            builder.json { config ->
                serde?.let { config.serde(it) }
                serializer?.let { config.serializer(it) }
                deserializer?.let { config.deserializer(it) }
                inputValidator?.let { config.inputSchemaValidator(it) }
                outputValidator?.let { config.outputSchemaValidator(it) }
            }
        }
    }
