/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.json;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A single schema validation error.
 *
 * @param path    JSON pointer to the failing field
 * @param keyword the validation keyword that failed
 * @param message human-readable error description
 */
public record SchemaValidationError(String path, String keyword, String message) {

    /**
     * Joins several errors into one {@code "; "}-separated string. Each error reads as
     * {@code "<path>: <message>"}; an error at the document root has no path prefix.
     *
     * @param errors the errors
     * @return the joined error messages
     */
    public static String join(List<SchemaValidationError> errors) {
        return errors.stream().map(SchemaValidationError::located).collect(Collectors.joining("; "));
    }

    /**
     * This error's message prefixed with its {@link #path()}, or the bare message at the document
     * root.
     *
     * @return {@code "<path>: <message>"}, or {@code message} when {@code path} is blank
     */
    public String located() {
        return path == null || path.isBlank() ? message : path + ": " + message;
    }
}
