// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("ktlint:standard:filename")
// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.

package dev.tachyonmcp.kotlin.server.json.ktschema

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.json.JsonSchema
import kotlinx.serialization.json.Json
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator

/**
 * Builds a `typedTool` `schemaGenerator` lambda backed by kt-schema's
 * [ReflectionClassJsonSchemaGenerator] with [config].
 *
 * Without an argument this is [JsonSchemaConfig.Lenient], the same preset [KtSchemaReflectionFactory]
 * applies to every `JsonSchema.generate` call: defaulted and nullable properties are
 * optional, extra properties are allowed, `$defs` use short names, and nullable properties carry
 * no null marker — an explicit `null` on the wire fails validation, so pair it with a serde that
 * drops nulls (kotlinx `explicitNulls = false`). `JsonSchemaConfig.Strict` lists every property as
 * `required`, Kotlin defaults included — pass the result as `typedTool(..., schemaGenerator =
 * ktSchemaGenerator(JsonSchemaConfig.Strict)) { }`.
 */
@ExperimentalApi
@JvmSynthetic
public fun ktSchemaGenerator(
    config: JsonSchemaConfig = JsonSchemaConfig.Lenient,
): (Class<*>) -> JsonSchema {
    val generator =
        ReflectionClassJsonSchemaGenerator(json = Json { encodeDefaults = false }, config = config)
    return { type -> JsonSchema.unchecked(generator.generateSchemaString(type.kotlin)) }
}
