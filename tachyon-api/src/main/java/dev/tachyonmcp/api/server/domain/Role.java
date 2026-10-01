/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

/**
 * The sender of a message in a prompt or conversation.
 *
 * <p>Two roles are defined: {@link #USER} for human messages and
 * {@link #ASSISTANT} for model/assistant responses. Serialization to/from the
 * wire string (e.g. {@code "user"}, {@code "assistant"}) is handled by
 * {@link #getValue()} and {@link #fromValue(String)}.
 */
public enum Role {
    /**
     * User.
     */
    USER("user"),
    /**
     * Assistant.
     */
    ASSISTANT("assistant");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    /**
     * Returns the wire string for this role ({@code "user"} or {@code "assistant"}).
     *
     * @return the wire string for this role ({@code "user"} or {@code "assistant"})
     */
    public String getValue() {
        return value;
    }

    /**
     * Parses a role from its wire string representation.
     *
     * @throws IllegalArgumentException when the value does not match any known role.
     * @param value the value
     * @return the matching role
     */
    public static Role fromValue(String value) {
        for (Role v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unexpected value: " + value);
    }
}
