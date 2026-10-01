/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

/** Logging levels matching the MCP protocol specification. Ordered by severity ascending. */
public enum LoggingLevel {
    /**
     * Debug.
     */
    DEBUG("debug"),
    /**
     * Info.
     */
    INFO("info"),
    /**
     * Notice.
     */
    NOTICE("notice"),
    /**
     * Warning.
     */
    WARNING("warning"),
    /**
     * Error.
     */
    ERROR("error"),
    /**
     * Critical.
     */
    CRITICAL("critical"),
    /**
     * Alert.
     */
    ALERT("alert"),
    /**
     * Emergency.
     */
    EMERGENCY("emergency");

    private final String value;

    LoggingLevel(String value) {
        this.value = value;
    }

    /**
     * Returns the wire-level string for this logging level.
     *
     * @return the wire-level string for this logging level
     */
    public String getValue() {
        return value;
    }

    /**
     * Parses a logging level from its wire-level string.
     *
     * @param value the value
     * @return the matching logging level
     */
    public static LoggingLevel fromValue(String value) {
        for (var v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unexpected value: " + value);
    }
}
