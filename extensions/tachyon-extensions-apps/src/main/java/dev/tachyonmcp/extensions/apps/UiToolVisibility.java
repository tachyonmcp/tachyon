/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.apps;

/**
 * Tool visibility scope - who can access the tool.
 */
public enum UiToolVisibility {
    /** The {@code "model"} value. */
    MODEL("model"),
    /** The {@code "app"} value. */
    APP("app");

    private final String value;

    UiToolVisibility(String value) {
        this.value = value;
    }

    /**
     * Returns the wire value of this constant.
     *
     * @return the wire value
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves a constant from its wire value.
     *
     * @param value the wire value
     * @return the matching constant
     * @throws IllegalArgumentException if no constant has the given value
     */
    public static UiToolVisibility fromValue(String value) {
        for (UiToolVisibility v : values()) {
            if (v.value.equals(value)) return v;
        }
        throw new IllegalArgumentException("Unexpected value: " + value);
    }
}
