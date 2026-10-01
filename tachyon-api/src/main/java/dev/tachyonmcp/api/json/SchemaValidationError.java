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
     * Joins the messages of several errors into one {@code "; "}-separated string.
     *
     * @param errors the errors
     * @return the joined error messages
     */
    public static String join(List<SchemaValidationError> errors) {
        return errors.stream().map(SchemaValidationError::message).collect(Collectors.joining("; "));
    }
}
