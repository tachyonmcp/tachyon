/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the request's {@code _meta} object into an annotated feature method.
 *
 * <p>Declared as {@code Map<String, Object>} the parameter receives the raw entries (empty map when
 * the client sent none). Declared as a record or POJO the entries are decoded through the configured
 * {@code PayloadDeserializer}; a missing {@code _meta} yields {@code null} for {@code @Nullable}
 * parameters and rejects the request otherwise.
 *
 * <p>Absence refers to the entire {@code _meta} object, not individual fields. Protocol
 * 2026-07-28 requires metadata envelope fields even without application metadata. An empty or
 * envelope-only object is decoded normally; missing POJO/record fields follow the configured
 * deserializer's behavior.
 *
 * <p>Only one metadata parameter is allowed per method. Scalar types, other map declarations,
 * and combining this annotation with {@link McpParam} are rejected at registration.
 *
 * <p>The parameter never contributes to input schema or prompt arguments.
 */
@Documented
@ExperimentalApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Meta {}
