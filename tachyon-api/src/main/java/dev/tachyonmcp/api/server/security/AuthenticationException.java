/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Rejects a request whose credentials are missing, malformed or invalid.
 *
 * <p>The message is logged at debug and never sent to the client. Only this exception rejects
 * credentials: any other exception from an {@link AuthenticationProvider} means they could not be
 * checked (an introspection endpoint is down) and answers {@code 500}, so a client does not discard
 * a token that may be valid.
 */
@ExperimentalApi
public class AuthenticationException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why a request was rejected, following RFC 6750 §3.1. */
    public enum Reason {
        /** No credentials: {@code 401} with a bare {@code WWW-Authenticate: Bearer} challenge. */
        MISSING_CREDENTIALS,
        /** Malformed credentials: {@code 400} with {@code error="invalid_request"}. */
        INVALID_REQUEST,
        /** Expired, revoked or otherwise invalid credentials: {@code 401} with {@code error="invalid_token"}. */
        INVALID_TOKEN
    }

    private final Reason reason;

    /**
     * Creates an exception with a reason and a diagnostic message.
     *
     * @param reason why the request is rejected
     * @param message diagnostic message; must not contain the credentials
     */
    public AuthenticationException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * Creates an exception with a reason, a diagnostic message and a cause.
     *
     * @param reason why the request is rejected
     * @param message diagnostic message; must not contain the credentials
     * @param cause the underlying failure, or {@code null}
     */
    public AuthenticationException(Reason reason, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns why the request was rejected.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
