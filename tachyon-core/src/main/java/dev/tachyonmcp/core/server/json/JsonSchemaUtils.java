/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import static dev.tachyonmcp.core.protocol.mcp.McpHeaderNames.X_MCP_HEADER;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.SchemaValidationError;
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory;
import dev.tachyonmcp.core.transport.netty.http.HttpHelpers;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@InternalApi
public class JsonSchemaUtils {

    /** {@code number} is excluded: its string form is not canonical, so header and body could differ. */
    private static final List<String> MIRRORABLE_TYPES = List.of("string", "integer", "boolean");

    private JsonSchemaUtils() {
        // noop
    }

    /**
     * Validates that a tool's {@code inputSchema} is well-formed JSON with an object root
     * declaring {@code "type": "object"}. Tool-call arguments are always a JSON object, so this
     * restriction is unconditional across every MCP protocol version.
     *
     * @param factory  parses and validates the schema's raw JSON; must handle {@link String}
     *     sources, otherwise schemas cannot be validated
     * @param toolName the name of the tool owning the schema
     * @param schema   the schema to validate, or {@code null}
     * @throws IllegalArgumentException if the schema is not valid JSON, or its root is invalid
     */
    public static void validateInputSchemaRoot(
            JsonSchemaFactory<?> factory, String toolName, @Nullable JsonSchema schema) {
        if (schema == null) return;
        var node = parseSchemaRoot(factory, "inputSchema", toolName, schema);
        final String detail;
        if (!node.isObject()) {
            detail = "got: " + node.getNodeType();
        } else if (!node.has("type")) {
            detail = "missing \"type\"";
        } else if (!"object".equals(node.get("type").asString())) {
            detail = "got: " + node.get("type");
        } else {
            return;
        }
        throw new IllegalArgumentException(
                "Tool '" + toolName + "' inputSchema root must declare \"type\": \"object\", " + detail);
    }

    /**
     * Validates that a tool's {@code outputSchema} is well-formed JSON with an object-shaped
     * schema container at its root. Unlike {@code inputSchema}, no {@code "type"} restriction is
     * enforced: MCP 2026-07-28 permits any valid JSON Schema 2020-12 as {@code outputSchema}
     * (object, array, or scalar root), while older protocol versions restrict {@code
     * structuredContent} to a JSON object on the wire — that fallback is handled at response-encode
     * time, not at registration.
     *
     * @param factory  parses and validates the schema's raw JSON; must handle {@link String}
     *     sources, otherwise schemas cannot be validated
     * @param toolName the name of the tool owning the schema
     * @param schema   the schema to validate, or {@code null}
     * @throws IllegalArgumentException if the schema is not valid JSON, or its root is invalid
     */
    public static void validateOutputSchemaRoot(
            JsonSchemaFactory<?> factory, String toolName, @Nullable JsonSchema schema) {
        if (schema == null) return;
        var node = parseSchemaRoot(factory, "outputSchema", toolName, schema);
        if (node.isObject()) return;
        throw new IllegalArgumentException(
                "Tool '" + toolName + "' outputSchema must be a JSON Schema object, got: " + node.getNodeType());
    }

    /**
     * Validates a tool's {@code x-mcp-header} annotations against SEP-2243, rejecting the tool
     * definition on violation — an annotation the server silently skips is a header intermediaries
     * route on but nothing compares to the body.
     *
     * <p>Only top-level properties may be mirrored. The SEP allows any nesting depth; this is a
     * Tachyon-only restriction, not a conformance requirement — {@code RequestValidationHandler} only
     * checks top-level {@code tools/call} arguments, so a nested annotation would go unvalidated and
     * leave the header spoofable.
     *
     * @param factory  parses and validates the schema's raw JSON; must handle {@link String} sources
     * @param toolName the name of the tool owning the schema
     * @param schema   the schema to validate, or {@code null}
     * @throws IllegalArgumentException if any {@code x-mcp-header} annotation is invalid
     */
    public static void validateHeaderAnnotations(
            JsonSchemaFactory<?> factory, String toolName, @Nullable JsonSchema schema) {
        if (schema == null) return;
        var root = parseSchemaRoot(factory, "inputSchema", toolName, schema);
        var topLevel = validateTopLevelAnnotations(root, toolName, new HashMap<>());
        if (countAnnotations(root) > topLevel) {
            throw new IllegalArgumentException("Tool '" + toolName + "' declares an " + X_MCP_HEADER
                    + " on a nested property; only top-level properties can be mirrored");
        }
    }

    /**
     * Validates the top-level annotations and returns how many there were, for comparison against
     * {@link #countAnnotations} — a higher total means one sits deeper in the schema.
     *
     * @param claimed header name, lowercased, to the property that claimed it
     */
    private static int validateTopLevelAnnotations(JsonNode root, String toolName, Map<String, String> claimed) {
        var properties = root.path("properties");
        if (!properties.isObject()) return 0;
        var count = 0;
        for (var entry : properties.properties()) {
            var propertySchema = entry.getValue();
            var annotation = propertySchema.path(X_MCP_HEADER);
            if (annotation.isMissingNode()) continue;
            if (!annotation.isString()) {
                throw new IllegalArgumentException("Tool '" + toolName + "' property '" + entry.getKey() + "' has an "
                        + X_MCP_HEADER + " that is not a string");
            }
            validateAnnotation(toolName, entry.getKey(), annotation.asString(), propertySchema, claimed);
            count++;
        }
        return count;
    }

    private static void validateAnnotation(
            String toolName, String property, String headerName, JsonNode propertySchema, Map<String, String> claimed) {
        if (!HttpHelpers.isToken(headerName)) {
            throw new IllegalArgumentException("Tool '" + toolName + "' property '" + property + "' has an "
                    + X_MCP_HEADER + " that is not a non-empty HTTP token: '" + headerName + "'");
        }
        var declaredType = propertySchema.path("type");
        var type = declaredType.isString() ? declaredType.asString() : null;
        if (!MIRRORABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Tool '" + toolName + "' property '" + property + "' declares "
                    + X_MCP_HEADER + " on type '" + type + "'; only " + MIRRORABLE_TYPES + " can be mirrored");
        }
        var previous = claimed.put(headerName.toLowerCase(Locale.ROOT), property);
        if (previous != null) {
            throw new IllegalArgumentException("Tool '" + toolName + "' maps both '" + previous + "' and '" + property
                    + "' to " + X_MCP_HEADER + " '" + headerName + "'; names must be unique ignoring case");
        }
    }

    /** Occurrences anywhere in the tree, to catch those off the {@code properties} chain. */
    private static int countAnnotations(JsonNode node) {
        return countAnnotations(node, false);
    }

    /**
     * @param propertyNames {@code true} when {@code node} is a {@code properties} map, whose keys are
     *     author-chosen property names rather than schema keywords — a property may legitimately be
     *     called {@code x-mcp-header} without being one
     */
    private static int countAnnotations(JsonNode node, boolean propertyNames) {
        if (!node.isObject()) {
            var elements = 0;
            for (var child : node) {
                elements += countAnnotations(child, false);
            }
            return elements;
        }
        var count = !propertyNames && !node.path(X_MCP_HEADER).isMissingNode() ? 1 : 0;
        for (var child : node.properties()) {
            count += countAnnotations(child.getValue(), "properties".equals(child.getKey()));
        }
        return count;
    }

    private static JsonNode parseSchemaRoot(
            JsonSchemaFactory<?> factory, String schemaKind, String toolName, JsonSchema schema) {
        if (factory.sourceType() != String.class) {
            throw new IllegalStateException(
                    "Configured schema factory '" + factory.getClass().getName()
                            + "' does not handle String sources and cannot validate the " + schemaKind
                            + " of tool '" + toolName + "'.");
        }
        @SuppressWarnings("unchecked")
        var validated = ((JsonSchemaFactory<String>) factory).toJsonSchema(schema.json());
        if (validated.isEmpty()) {
            throw new IllegalStateException(
                    "Configured schema factory does not handle String sources and cannot validate the " + schemaKind
                            + " of tool '" + toolName + "'.");
        }
        return JsonUtils.parse(validated.get());
    }

    /**
     * Validates args against schema; returns a joined error message, or null when valid / no schema.
     */
    public static @Nullable String validateArguments(
            JsonSchemaValidator validator, @Nullable JsonSchema schema, @Nullable Map<String, JsonNode> args) {
        if (schema == null) return null;
        var document = JsonDocument.of(JsonUtils.writeString(args == null ? Map.of() : args));
        var errors = validator.validate(schema, document);
        return errors.isEmpty() ? null : SchemaValidationError.join(errors);
    }
}
