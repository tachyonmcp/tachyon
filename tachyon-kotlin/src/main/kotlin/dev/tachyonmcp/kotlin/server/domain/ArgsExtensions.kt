// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("TooManyFunctions")
@file:JvmSynthetic

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.json.JsonArray
import dev.tachyonmcp.api.json.JsonObject
import dev.tachyonmcp.api.server.domain.Args
import java.math.BigDecimal

/** Returns the [key] argument as a [String], or `null` when it is missing or JSON null. */
public fun Args.stringOrNull(key: String): String? = stringOpt(key).orElse(null)

/**
 * Returns the [key] argument as an [Int], or `null` when it is missing or JSON null.
 * Fractional or overflowing values throw.
 */
public fun Args.intOrNull(key: String): Int? =
    intOpt(key).let { if (it.isPresent) it.asInt else null }

/** Returns the [key] argument as a [Boolean], or `null` when it is missing or JSON null. */
public fun Args.booleanOrNull(key: String): Boolean? = boolOpt(key).orElse(null)

/** Returns the [key] argument as a [Double], or `null` when it is missing or JSON null. */
public fun Args.doubleOrNull(key: String): Double? =
    doubleOpt(key).let { if (it.isPresent) it.asDouble else null }

/**
 * Returns the [key] argument as a [Long], or `null` when it is missing or JSON null.
 * Fractional or overflowing values throw.
 */
public fun Args.longOrNull(key: String): Long? =
    longOpt(key).let { if (it.isPresent) it.asLong else null }

/** Returns the [key] argument as a [JsonObject], or `null` when it is missing or JSON null. */
public fun Args.objectOrNull(key: String): JsonObject? = objectOpt(key).orElse(null)

/** Returns the [key] argument as an exact [BigDecimal], or `null` when it is missing or JSON null. */
public fun Args.decimalOrNull(key: String): BigDecimal? = decimalOpt(key).orElse(null)

/** Returns the [key] argument as a [dev.tachyonmcp.api.json.JsonArray], or `null` when it is missing or JSON null. */
public fun Args.arrayOrNull(key: String): JsonArray? = arrayOpt(key).orElse(null)

/** Returns the [key] argument as a [String], or [default] when it is missing or JSON null. */
public fun Args.string(
    key: String,
    default: String,
): String = stringOr(key, default)

/** Returns the [key] argument as a [Boolean], or [default] when it is missing or JSON null. */
public fun Args.boolean(
    key: String,
    default: Boolean,
): Boolean = boolOr(key, default)

/** Returns the [key] argument as an [Int], or [default] when it is missing or JSON null. */
public fun Args.int(
    key: String,
    default: Int,
): Int = intOr(key, default)

/** Returns the [key] argument as a [Long], or [default] when it is missing or JSON null. */
public fun Args.long(
    key: String,
    default: Long,
): Long = longOr(key, default)

/** Returns the [key] argument as a [Double], or [default] when it is missing or JSON null. */
public fun Args.double(
    key: String,
    default: Double,
): Double = doubleOr(key, default)

/**
 * Returns every element of this array coerced to [T]. Supported element types match
 * [JsonArray.valuesAs]; a null, wrong-typed, or overflowing element throws.
 */
public inline fun <reified T : Any> JsonArray.valuesAs(): List<T> = valuesAs(T::class.java)

/**
 * Decodes tool arguments into [T] using the deserializer configured in server config.
 * The Kotlin DSL retains Tachyon's Jackson default. To use kotlinx serialization, configure
 * `json { serde = KxSerializationSerde(Json { … }) }`.
 * Generic type arguments of containers are erased at runtime — prefer dedicated
 * [@Serializable][kotlinx.serialization.Serializable] payload classes.
 *
 * @throws IllegalStateException if no deserializer is configured for these args.
 */
public inline fun <reified T : Any> Args.decode(): T = decode(T::class.java)
