// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:Suppress("FunctionName")
@file:[JvmName("InputRequests") JvmSynthetic]

// Copyright (c) 2026 Konstantin Pavlov and contributors.

package dev.tachyonmcp.kotlin.server.domain

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.domain.FormInputRequest
import dev.tachyonmcp.api.server.domain.RpcMethodRequest
import dev.tachyonmcp.api.server.domain.UrlInputRequest
import dev.tachyonmcp.kotlin.server.json.toJacksonNode
import kotlinx.serialization.json.JsonObject

/**
 * Creates an [RpcMethodRequest] — requests user input by invoking an RPC method.
 *
 * @param method the RPC method to invoke
 * @param params optional parameters for the method; null to omit
 */
public fun RpcMethodRequest(
    method: String,
    params: Any? = null,
): RpcMethodRequest =
    RpcMethodRequest
        .of(method, params)

/**
 * Creates an [RpcMethodRequest] using a kotlinx-serialization [JsonObject] params.
 * The params are converted to the wire format, so they serialize correctly.
 */
public fun RpcMethodRequest(
    method: String,
    params: JsonObject,
): RpcMethodRequest =
    RpcMethodRequest.of(
        method,
        params.toJacksonNode(),
    )

/**
 * Creates a [FormInputRequest] — requests user input via a form described by a JSON schema.
 *
 * @param message         prompt shown to the user
 * @param requestedSchema JSON schema describing the expected form fields
 */
@Deprecated(
    message = "use `FormInputRequest` with JsonSchema instead",
    replaceWith =
        ReplaceWith(
            "FormInputRequest.of(message, JsonSchema.from(requestedSchema, Map::class.java))",
            "dev.tachyonmcp.api.server.domain.FormInputRequest",
            "dev.tachyonmcp.api.json.JsonSchema",
        ),
)
public fun FormInputRequest(
    message: String,
    requestedSchema: Map<String, Any>,
): FormInputRequest =
    FormInputRequest
        .of(message, JsonSchema.from(requestedSchema, Map::class.java))

/**
 * Creates a [FormInputRequest] — requests user input via a form described by a JSON schema.
 *
 * @param message         prompt shown to the user
 * @param requestedSchema JSON schema describing the expected form fields
 */
public fun FormInputRequest(
    message: String,
    requestedSchema: JsonSchema,
): FormInputRequest = FormInputRequest.of(message, requestedSchema)

/**
 * Creates a [UrlInputRequest] — requests user input by opening a URL.
 *
 * @param message       prompt shown to the user
 * @param elicitationId identifier linking the response to the elicitation context
 * @param url           URL to open for user input
 */
public fun UrlInputRequest(
    message: String,
    elicitationId: String,
    url: String,
): UrlInputRequest =
    UrlInputRequest
        .of(message, elicitationId, url)
