// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.json.ktschema

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator
import java.util.Optional

/**
 * [JsonSchemaFactory] that generates a schema at runtime by reflecting on the class, backed by
 * kt-schema's [ReflectionClassJsonSchemaGenerator]. Runs after the build-time resource factory
 * (tachyon-core's `KtSchemaResourceFactory`): generates a schema whenever no codegen resource
 * exists for the type.
 *
 * Ships in the dedicated `tachyon-kotlin-kt-schema` integration artifact, which declares
 * `kt-schema-generator-json-jvm` as a regular (non-optional) dependency. The provider therefore
 * always loads once that artifact is on the classpath — add it explicitly to use `typedTool`.
 *
 * Declines (returns empty) types the Kotlin reflection generator cannot describe, such as Java
 * records with primitive components, so the chain continues to tachyon-core's Java generator.
 */
@ExperimentalApi
internal class KtSchemaReflectionFactory : JsonSchemaFactory<Class<*>> {
    private val generator = ReflectionClassJsonSchemaGenerator.Default

    override fun sourceType(): Class<Class<*>> = Class::class.java

    override fun priority(): Int = 10

    override fun toJsonSchema(type: Class<*>): Optional<JsonSchema> =
        runCatching { generator.generateSchemaString(type.kotlin) }
            .map { Optional.of(JsonSchema.unchecked(it)) }
            .getOrElse { Optional.empty() }
}
