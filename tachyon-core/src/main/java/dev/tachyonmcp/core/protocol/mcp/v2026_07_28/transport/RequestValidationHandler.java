/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport;

import static dev.tachyonmcp.core.protocol.mcp.McpHeaderNames.X_MCP_HEADER;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Validates every 2026-07-28 POST request against the requirements that only apply once a request
 * is self-describing (no protocol-level session): required {@code _meta} fields (SEP-2575), the
 * {@code Mcp-Method}/{@code Mcp-Name}/{@code Mcp-Param-*} headers matching the body (SEP-2243), and
 * rejection of methods the draft removed. Runs after {@code http-aggregator} (needs the parsed
 * body) and before the initialize/operation phase handlers, so a rejected request never reaches
 * dispatch.
 *
 * <p>One instance per server (constructed with that server's {@link ServerEngine} to resolve
 * {@code x-mcp-header} tool-schema annotations for {@code Mcp-Param-*} validation), added to every
 * channel's pipeline unconditionally and no-ops for any request that didn't negotiate 2026-07-28 —
 * see the sibling {@code v2025_11_25.transport.RequestValidationHandler}.
 */
@Sharable
public final class RequestValidationHandler extends ChannelInboundHandlerAdapter {

    private static final Set<String> REMOVED_METHODS =
            Set.of("initialize", "ping", "logging/setLevel", "resources/subscribe", "resources/unsubscribe");
    private static final Set<String> NAME_REQUIRED_METHODS = Set.of("tools/call", "resources/read", "prompts/get");

    private static final String META = "_meta";
    private static final String PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_INFO_KEY = "io.modelcontextprotocol/clientInfo";
    private static final String CLIENT_CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";

    /** 2^53-1. Past it a JSON number cannot round-trip through a JavaScript intermediary intact. */
    private static final BigDecimal MAX_SAFE_INTEGER = BigDecimal.valueOf(9007199254740991L);

    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    private final ServerEngine server;

    public RequestValidationHandler(ServerEngine server) {
        this.server = server;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest req) || req.method() != HttpMethod.POST) {
            ctx.fireChannelRead(msg);
            return;
        }
        var interaction = ChannelHandlerUtils.getInteractionContext(ctx);
        if (interaction == null || !McpProtocol.VERSION.equals(interaction.protocolVersion())) {
            ctx.fireChannelRead(msg);
            return;
        }

        JsonRpcMessage message;
        try {
            // A duplicate view shares the backing memory but has its own reader index, so peeking
            // here doesn't disturb what the operation/init handler reads from req.content() next.
            message = JsonRpcCodec.parseRequest(req.content().duplicate());
        } catch (RuntimeException e) {
            // Malformed JSON: let the normal parse-error path downstream handle it.
            ctx.fireChannelRead(msg);
            return;
        }
        if (message instanceof JsonRpcMessage.Notification<?> notification) {
            // Mcp-Method applies to notifications too; Mcp-Name/Mcp-Param-* are request-scoped, but
            // the char-format rule isn't. No id to echo, so the error carries null.
            var rejection = validateMethodHeader(req, notification.method());
            if (rejection == null) rejection = validateParamHeaderChars(req);
            if (rejection != null) {
                reject(ctx, req, null, rejection);
                return;
            }
            ctx.fireChannelRead(msg);
            return;
        }
        if (!(message instanceof JsonRpcMessage.Request<?> request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        var rejection = validate(req, request);
        if (rejection != null) {
            reject(ctx, req, request.id(), rejection);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private @Nullable ServerError validate(FullHttpRequest req, JsonRpcMessage.Request<?> request) {
        var method = request.method();
        if (REMOVED_METHODS.contains(method)) {
            return ServerErrors.methodNotFound("Method not found");
        }

        var params = paramsNode(request.params());
        var meta = params.get(META);
        if (meta == null || !meta.isObject()) {
            return ServerErrors.invalidParams("Missing required " + META);
        }
        var rawProtocolVersion = meta.get(PROTOCOL_VERSION_KEY);
        if (rawProtocolVersion == null || !rawProtocolVersion.isString()) {
            return ServerErrors.invalidParams(META + " missing required field: " + PROTOCOL_VERSION_KEY);
        }
        var metaProtocolVersion = rawProtocolVersion.stringValue();
        var clientInfo = meta.get(CLIENT_INFO_KEY);
        if (clientInfo != null
                && !(clientInfo.isObject()
                        && clientInfo.path("name").isString()
                        && clientInfo.path("version").isString())) {
            return ServerErrors.invalidParams(META + " invalid field: " + CLIENT_INFO_KEY);
        }
        if (!meta.path(CLIENT_CAPABILITIES_KEY).isObject()) {
            return ServerErrors.invalidParams(META + " missing required field: " + CLIENT_CAPABILITIES_KEY);
        }

        var headerProtocolVersion = strip(req.headers().get(McpHeaderNames.MCP_PROTOCOL_VERSION));
        if (!metaProtocolVersion.equals(headerProtocolVersion)) {
            return ServerErrors.headerMismatch("Header mismatch: " + McpHeaderNames.MCP_PROTOCOL_VERSION
                    + " header value '" + headerProtocolVersion + "' does not match body " + META + "."
                    + PROTOCOL_VERSION_KEY + " value '" + metaProtocolVersion + "'");
        }

        var methodRejection = validateMethodHeader(req, method);
        if (methodRejection != null) return methodRejection;

        if (NAME_REQUIRED_METHODS.contains(method)) {
            var bodyName = "resources/read".equals(method)
                    ? asString(params.get("uri"))
                    : asString(params.get("name"));
            var headerName = decodeName(req.headers().get(McpHeaderNames.MCP_NAME));
            if (headerName == null || !headerName.equals(bodyName)) {
                return ServerErrors.headerMismatch("Header mismatch: " + McpHeaderNames.MCP_NAME + " header value '"
                        + headerName + "' does not match body value '" + bodyName + "'");
            }
        }

        var charRejection = validateParamHeaderChars(req);
        if (charRejection != null) return charRejection;

        if ("tools/call".equals(method)) {
            var toolCallRejection = validateCustomParamHeaders(req, params);
            if (toolCallRejection != null) return toolCallRejection;
        }

        return null;
    }

    /**
     * Not scoped to tools/call: the rule is about the field's wire format, matched or not.
     */
    private static @Nullable ServerError validateParamHeaderChars(HttpRequest req) {
        var invalidHeader = findInvalidCharacterParamHeader(req);
        return invalidHeader == null
                ? null
                : ServerErrors.headerMismatch("Header mismatch: " + invalidHeader
                        + " contains characters not permitted in an HTTP field value");
    }

    /**
     * Validates {@code Mcp-Param-{Name}} headers against the tool's {@code x-mcp-header}-annotated
     * properties (SEP-2243). Registration rejects annotations below the top level, so every one the
     * schema carries is checked here.
     */
    private @Nullable ServerError validateCustomParamHeaders(HttpRequest req, JsonNode params) {
        var toolName = asString(params.get("name"));
        if (toolName == null) return null;
        var descriptor = server.tools().find(toolName).orElse(null);
        var inputSchema = descriptor != null ? descriptor.inputSchema() : null;
        if (inputSchema == null) return null;
        var properties = JsonUtils.parse(inputSchema).path("properties");
        if (!properties.isObject()) return null;
        var arguments = params.path("arguments");

        for (var entry : properties.properties()) {
            var headerAnnotation = entry.getValue().path(X_MCP_HEADER);
            if (!headerAnnotation.isString()) continue;
            var propertyName = entry.getKey();
            // Only the mirrored argument is flattened: matching compares against the header's
            // scalar text, and annotated properties are a small subset of the arguments.
            var argument = arguments.isObject() ? arguments.get(propertyName) : null;
            var rejection = validateMirroredValue(
                    req,
                    McpHeaderNames.MCP_PARAM_PREFIX + headerAnnotation.asString(),
                    propertyName,
                    argument == null ? null : JsonUtils.mapper().treeToValue(argument, Object.class));
            if (rejection != null) return rejection;
        }
        return null;
    }

    private static @Nullable ServerError validateMirroredValue(
            HttpRequest req, String headerName, String propertyName, @Nullable Object argument) {
        var bodyValue = argumentAsHeaderString(argument);
        if (bodyValue == null) {
            // Client MUST omit the header when the argument is absent/null/non-primitive; a header
            // present anyway has nothing in the body backing the value a gateway would route on.
            return req.headers().get(headerName) == null
                    ? null
                    : ServerErrors.headerMismatch(
                            "Header mismatch: " + headerName + " is present but body has no matching value");
        }
        if (argument instanceof Number n) {
            BigDecimal decimal;
            try {
                decimal = new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                // NaN/Infinity: not a BigDecimal literal. Value not echoed: it is client-controlled.
                return ServerErrors.headerMismatch("Header mismatch: " + headerName + " value is not a finite number");
            }
            if (decimal.abs().compareTo(MAX_SAFE_INTEGER) > 0) {
                return ServerErrors.headerMismatch("Header mismatch: " + headerName + " value '" + bodyValue
                        + "' is outside the safe integer range and cannot be mirrored");
            }
        }
        var rawHeader = req.headers().get(headerName);
        if (rawHeader == null) {
            return ServerErrors.headerMismatch("Header mismatch: " + headerName
                    + " is required because body arguments contains '" + propertyName + "'");
        }
        String decodedHeader;
        try {
            decodedHeader = decodeParamValue(rawHeader);
        } catch (IllegalArgumentException e) {
            return ServerErrors.headerMismatch("Header mismatch: " + headerName + " has invalid Base64 encoding");
        }
        if (!matches(argument, bodyValue, decodedHeader)) {
            return ServerErrors.headerMismatch("Header mismatch: " + headerName + " header value '" + decodedHeader
                    + "' does not match body value '" + bodyValue + "'");
        }
        return null;
    }

    /**
     * SEP-2243: numbers compare numerically, so {@code 42} and {@code 42.0} are the same value. That
     * also stops a spoof — JSON Schema accepts {@code 42.0} as an {@code integer}, so a string-only
     * comparison would have to skip it, leaving the header unchecked.
     */
    private static boolean matches(@Nullable Object argument, String bodyValue, String decodedHeader) {
        if (!(argument instanceof Number)) {
            return decodedHeader.equals(bodyValue);
        }
        try {
            return new BigDecimal(decodedHeader).compareTo(new BigDecimal(bodyValue)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Name of the first {@code Mcp-Param-*} header violating SEP-2243's character restrictions, or
     * {@code null}. Read before Base64 decoding: the rule applies to what travelled on the wire.
     */
    private static @Nullable CharSequence findInvalidCharacterParamHeader(HttpRequest req) {
        var it = req.headers().iteratorCharSequence();
        while (it.hasNext()) {
            var entry = it.next();
            if (McpHeaderNames.isParamHeader(entry.getKey()) && hasInvalidHeaderCharacter(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * SEP-2243 permits only HTAB, space and visible ASCII; anything else must travel Base64-wrapped.
     * Netty rejects NUL/CR/LF already, but passes {@code 0x80}-{@code 0xFF} through as ISO-8859-1 —
     * that non-ASCII case is what this catches.
     */
    private static boolean hasInvalidHeaderCharacter(CharSequence value) {
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7E)) {
                return true;
            }
        }
        return false;
    }

    /**
     * String form of an {@code x-mcp-header}-eligible argument value, per the Value Encoding type
     * conversion rules (string as-is, integer as decimal, boolean lowercase); {@code null} if the
     * value is absent/null or not one of those primitive types.
     */
    private static @Nullable String argumentAsHeaderString(@Nullable Object value) {
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

    private static @Nullable ServerError validateMethodHeader(HttpRequest req, String method) {
        var headerMethod = strip(req.headers().get(McpHeaderNames.MCP_METHOD));
        if (method.equals(headerMethod)) return null;
        return ServerErrors.headerMismatch("Header mismatch: " + McpHeaderNames.MCP_METHOD + " header value '"
                + headerMethod + "' does not match body method '" + method + "'");
    }

    private void reject(ChannelHandlerContext ctx, FullHttpRequest req, @Nullable RequestId id, ServerError error) {
        var interaction = ChannelHandlerUtils.requireInteractionContext(ctx);
        var wireError = interaction.protocol().responseMapper().error(error);
        var body = JsonRpcCodec.serializeError(id, wireError.code(), wireError.message(), wireError.data());
        var origin = req.headers().get(HttpHeaderNames.ORIGIN);
        req.release();
        ChannelHandlerUtils.sendResponseAndClose(
                ctx, HttpResponseStatus.valueOf(wireError.httpStatus()), "application/json", body, origin);
    }

    /** Narrows a raw {@code params} payload to the object node this validator reads. */
    private static JsonNode paramsNode(@Nullable Object params) {
        if (params instanceof JsonNode node && node.isObject()) return node;
        if (params instanceof Map<?, ?> map) return JsonUtils.toObjectNode(stringKeyed(map));
        return JsonUtils.mapper().createObjectNode();
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (key instanceof String text) result.put(text, value);
        });
        return result;
    }

    private static @Nullable String asString(@Nullable JsonNode value) {
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static @Nullable String strip(@Nullable String value) {
        return value == null ? null : value.strip();
    }

    /**
     * Decodes the Base64 sentinel format ({@code =?base64?<value>?=}) used to carry non-ASCII or
     * whitespace-containing {@code Mcp-Name} values, and strips OWS from plain values (RFC 9110
     * field-value parsing excludes leading/trailing whitespace before comparison).
     */
    private static @Nullable String decodeName(@Nullable String raw) {
        if (raw == null) return null;
        var trimmed = raw.strip();
        if (trimmed.length() >= BASE64_PREFIX.length() + BASE64_SUFFIX.length()
                && trimmed.startsWith(BASE64_PREFIX)
                && trimmed.endsWith(BASE64_SUFFIX)) {
            var encoded = trimmed.substring(BASE64_PREFIX.length(), trimmed.length() - BASE64_SUFFIX.length());
            try {
                return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return trimmed;
    }

    /**
     * Decodes an {@code Mcp-Param-*} header value: the Base64 sentinel wrapper if present, else the
     * literal value. Unlike {@link #decodeName}, invalid Base64 inside the wrapper is a distinct
     * failure the caller must reject explicitly (SEP-2243: "Server MUST reject requests with invalid
     * Base64 padding or characters"), so this throws rather than degrading to a plain mismatch.
     */
    private static String decodeParamValue(String raw) {
        if (raw.length() >= BASE64_PREFIX.length() + BASE64_SUFFIX.length()
                && raw.startsWith(BASE64_PREFIX)
                && raw.endsWith(BASE64_SUFFIX)) {
            var encoded = raw.substring(BASE64_PREFIX.length(), raw.length() - BASE64_SUFFIX.length());
            // Base64.getDecoder() tolerates missing padding (e.g. "SGVsbG8" for "Hello"), but SEP-2243
            // requires rejecting malformed padding, not just invalid alphabet characters.
            if (encoded.length() % 4 != 0) {
                throw new IllegalArgumentException("invalid Base64 padding");
            }
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return raw;
    }
}
