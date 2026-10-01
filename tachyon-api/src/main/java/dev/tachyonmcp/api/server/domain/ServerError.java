/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import org.jspecify.annotations.Nullable;

/**
 * A protocol-neutral server error returned by server code.
 *
 * @param kind    the error kind
 * @param message the error description
 * @param data    optional additional error data
 */
public record ServerError(
        Kind kind, String message, @Nullable Object data) {

    /**
     * Categorizes the nature of a server error.
     */
    public enum Kind {
        /** The request could not be parsed as valid JSON. */
        PARSE_ERROR,
        /** The request was not a valid JSON-RPC request. */
        INVALID_REQUEST,
        /** The requested method was not found. */
        METHOD_NOT_FOUND,
        /** The method parameters were invalid. */
        INVALID_PARAMS,
        /** An internal server error occurred. */
        INTERNAL_ERROR,
        /** The requested resource was not found. */
        RESOURCE_NOT_FOUND,
        /** A required header did not match expectations. */
        HEADER_MISMATCH,
        /** The client is missing a required capability. */
        MISSING_REQUIRED_CLIENT_CAPABILITY,
        /** The protocol version is not supported. */
        UNSUPPORTED_PROTOCOL_VERSION
    }

    /**
     * Creates a server error.
     *
     * @param kind the kind
     * @param message the message
     */
    public ServerError(Kind kind, String message) {
        this(kind, message, null);
    }
}
