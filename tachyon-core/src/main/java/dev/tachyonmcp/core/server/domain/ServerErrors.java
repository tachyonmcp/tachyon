/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.domain;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import dev.tachyonmcp.api.server.domain.ServerError;
import java.util.Map;

/** Factories for protocol-neutral server errors. */
public final class ServerErrors {

    private ServerErrors() {}

    public static ServerError parseError() {
        return new ServerError(ServerError.Kind.PARSE_ERROR, "Parse error");
    }

    public static ServerError invalidRequest(String detail) {
        return new ServerError(ServerError.Kind.INVALID_REQUEST, detail);
    }

    public static ServerError methodNotFound(String detail) {
        return new ServerError(ServerError.Kind.METHOD_NOT_FOUND, detail);
    }

    public static ServerError invalidParams(String detail) {
        return new ServerError(ServerError.Kind.INVALID_PARAMS, detail);
    }

    public static ServerError internalError(String detail) {
        return new ServerError(ServerError.Kind.INTERNAL_ERROR, detail);
    }

    /**
     * Default mapping for an exception a handler didn't translate to a wire error itself. A bare
     * {@link IllegalArgumentException} is treated as bad input (INVALID_PARAMS) without echoing its
     * message, which may originate from arbitrary library code and isn't vetted for sensitive
     * content — unlike {@link InvalidArgumentException}, which handler authors throw deliberately
     * with a message they control. {@link MissingRequiredClientCapabilityException} maps to its own
     * wire error. Everything else is INTERNAL_ERROR.
     */
    @InternalApi
    public static ServerError fromUnhandledException(Throwable cause, String internalErrorDetail) {
        return switch (cause) {
            case InvalidArgumentException invalid ->
                invalidParams("invalid argument '" + invalid.argName() + "': " + invalid.getMessage());
            case MissingRequiredClientCapabilityException missing ->
                missingRequiredClientCapability(missing.getMessage(), missing.requiredCapabilities());
            case IllegalArgumentException illegalArgumentException -> invalidParams("Invalid params");
            default -> internalError(internalErrorDetail);
        };
    }

    public static ServerError resourceNotFound(String detail) {
        return new ServerError(ServerError.Kind.RESOURCE_NOT_FOUND, detail);
    }

    public static ServerError resourceNotFound(String detail, Map<String, String> data) {
        return new ServerError(ServerError.Kind.RESOURCE_NOT_FOUND, detail, data);
    }

    public static ServerError headerMismatch(String detail) {
        return new ServerError(ServerError.Kind.HEADER_MISMATCH, detail);
    }

    /** {@code error.data.requiredCapabilities} is a {@code ClientCapabilities}-shaped map, not a name list. */
    public static ServerError missingRequiredClientCapability(String detail, Map<String, Object> requiredCapabilities) {
        return new ServerError(
                ServerError.Kind.MISSING_REQUIRED_CLIENT_CAPABILITY,
                detail,
                Map.of("requiredCapabilities", requiredCapabilities));
    }

    /** The client did not declare {@code extensionId}; {@code requiredCapabilities} names it under {@code extensions}. */
    public static ServerError missingRequiredExtension(String extensionId) {
        return missingRequiredClientCapability(
                "Requires the '" + extensionId + "' extension", Map.of("extensions", Map.of(extensionId, Map.of())));
    }

    public static ServerError unsupportedProtocolVersion(String detail, Map<String, Object> data) {
        return new ServerError(ServerError.Kind.UNSUPPORTED_PROTOCOL_VERSION, detail, data);
    }
}
