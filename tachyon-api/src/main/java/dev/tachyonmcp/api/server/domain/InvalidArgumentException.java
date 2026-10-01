/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

/**
 * Thrown when a method argument cannot be decoded or validated.
 * <p>
 * The dispatcher maps this exception to an {@code invalid-params} JSON-RPC error response
 * rather than an internal failure.
 */
public final class InvalidArgumentException extends IllegalArgumentException {

    /**
     * Arg name.
     */
    private final String argName;

    /**
     * Constructs a new {@code InvalidArgumentException}.
     *
     * @param argName the name of the invalid argument
     * @param message the detail message
     */
    public InvalidArgumentException(String argName, String message) {
        super(message);
        this.argName = argName;
    }

    /**
     * Constructs a new {@code InvalidArgumentException} with a cause.
     *
     * @param argName the name of the invalid argument
     * @param message the detail message
     * @param cause   the cause
     */
    public InvalidArgumentException(String argName, String message, Throwable cause) {
        super(message, cause);
        this.argName = argName;
    }

    /**
     * Returns the name of the invalid argument.
     *
     * @return the argument name
     */
    public String argName() {
        return argName;
    }
}
