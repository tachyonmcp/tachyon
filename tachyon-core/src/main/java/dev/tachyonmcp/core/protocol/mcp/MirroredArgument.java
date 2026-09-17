/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.core.server.json.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One tool argument a tool's input schema asks a caller to mirror into an {@code Mcp-Param-*}
 * header, paired with the value this particular call passed for it — the pairing both header rules
 * are expressed over. That a mirror must be <em>present</em> is a rule of the revision that adopted
 * SEP-2243; that a mirror which is present must <em>agree</em> with the body is a rule of every
 * revision. Each rule lives in its own handler and resolves the pairing for itself, so a
 * {@code tools/call} on 2026-07-28 walks the schema twice — once per handler, both on the event
 * loop, against an already-registered schema.
 *
 * @param headerName the {@code Mcp-Param-*} header this argument mirrors into
 * @param propertyName the argument's name in the tool's input schema
 * @param value the value the request passed, or {@code null} if it passed none
 */
@InternalApi
public record MirroredArgument(
        String headerName, String propertyName, @Nullable Object value) {

    /**
     * Every mirrored argument of the tool named by {@code params}, in schema order. Empty unless
     * {@code method} is {@code tools/call}: SEP-2243 scopes {@code x-mcp-header} to tools, the only
     * primitive whose definition carries an {@code inputSchema} to annotate. {@code prompts/get} has
     * the same {@code {name, arguments}} shape, so an ungated lookup would resolve a prompt's name
     * against the tool registry and hold the prompt to a same-named tool's rules. Empty too when the
     * call names no registered tool or the tool's schema annotates nothing — registration rejects
     * an {@code x-mcp-header} annotation below the top level, so every one the schema carries is
     * resolved here.
     *
     * @param tools the registry to resolve the call's tool name against
     * @param method the JSON-RPC method name from the request body
     * @param params the request's params object
     * @return the mirrored arguments, never {@code null}
     */
    public static List<MirroredArgument> of(Tools tools, String method, JsonNode params) {
        if (!"tools/call".equals(method)) return List.of();
        var toolName = params.path("name");
        if (!toolName.isString()) return List.of();
        var descriptor = tools.find(toolName.stringValue()).orElse(null);
        var inputSchema = descriptor != null ? descriptor.inputSchema() : null;
        if (inputSchema == null) return List.of();
        var properties = JsonUtils.parse(inputSchema).path("properties");
        if (!properties.isObject()) return List.of();

        var arguments = params.path("arguments");
        var mirrored = new ArrayList<MirroredArgument>();
        for (var entry : properties.properties()) {
            var annotation = entry.getValue().path(McpHeaderNames.X_MCP_HEADER);
            if (!annotation.isString()) continue;
            // Only the mirrored argument is flattened: matching compares against the header's scalar
            // text, and annotated properties are a small subset of the arguments.
            var argument = arguments.isObject() ? arguments.get(entry.getKey()) : null;
            mirrored.add(new MirroredArgument(
                    McpHeaderNames.MCP_PARAM_PREFIX + annotation.asString(),
                    entry.getKey(),
                    argument == null ? null : JsonUtils.mapper().treeToValue(argument, Object.class)));
        }
        return mirrored;
    }

    /**
     * The string this argument must appear as in its header, per SEP-2243's Value Encoding
     * conversion rules (string as-is, number as decimal, boolean lowercase), or {@code null} when
     * the request passed nothing mirrorable — an absent or JSON-{@code null} value, or a structured
     * one an HTTP field value cannot carry. A {@code null} here means the header must be absent
     * too, on every revision: there is no body value behind it to route on.
     *
     * @return the header form of {@link #value}, or {@code null} if it has none
     */
    public @Nullable String bodyValue() {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> String.valueOf(b);
            // Every Number, not just the integral ones: a value the schema calls an integer can still
            // decode to Double (42.0), and returning null there would skip the header check.
            case Number n -> n.toString();
            default -> null;
        };
    }
}
