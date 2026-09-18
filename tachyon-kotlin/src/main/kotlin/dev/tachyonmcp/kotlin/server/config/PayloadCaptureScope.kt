// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.core.server.config.PayloadCapturePolicy
import dev.tachyonmcp.kotlin.server.TachyonDsl

/** Kotlin DSL scope for the opt-in payload and exception-detail capture policy. */
@TachyonDsl
public class PayloadCaptureScope
    internal constructor() {
        /** Capture the request's params/arguments. */
        public var requestArgs: Boolean? = null

        /** Capture the encoded response content. */
        public var responseContent: Boolean? = null

        /** Capture the raw JSON-RPC envelope. */
        public var rawMessage: Boolean? = null

        /** Capture a redacted exception message on handler/serialization failure. */
        public var exceptionDetail: Boolean? = null

        /** Truncation limit, in UTF-8 bytes, applied to every captured value. */
        public var maxBytes: Int? = null

        internal fun applyTo(builder: PayloadCapturePolicy.Builder) {
            requestArgs?.let(builder::requestArgs)
            responseContent?.let(builder::responseContent)
            rawMessage?.let(builder::rawMessage)
            exceptionDetail?.let(builder::exceptionDetail)
            maxBytes?.let(builder::maxBytes)
        }
    }
