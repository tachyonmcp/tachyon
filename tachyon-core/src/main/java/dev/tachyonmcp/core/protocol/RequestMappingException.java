/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol;

import dev.tachyonmcp.api.server.domain.ServerError;

/** Signals that protocol request parameters could not be mapped to a domain request. */
public final class RequestMappingException extends RuntimeException {

    private final ServerError error;

    /**
     * @param error the mapping failure to surface as a protocol-level error response
     */
    public RequestMappingException(ServerError error) {
        super(error.message());
        this.error = error;
    }

    /**
     * @param error the mapping failure to surface as a protocol-level error response
     * @param cause the underlying failure
     */
    public RequestMappingException(ServerError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    /**
     * @return the {@link ServerError} to return to the client
     */
    public ServerError error() {
        return error;
    }
}
