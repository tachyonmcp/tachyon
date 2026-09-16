/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderValue;
import dev.tachyonmcp.core.protocol.mcp.MirroredArgument;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.core.transport.netty.PeekedBody;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Rejects a request whose SEP-2243 header mirror disagrees with its body, on every protocol
 * version.
 *
 * <p>SEP-2243 mirrors routing-critical values ({@code Mcp-Method}, {@code Mcp-Name},
 * {@code Mcp-Param-*}) into HTTP headers precisely so gateways, WAFs and rate limiters can decide
 * without parsing the JSON body — which only holds while both views agree. A mirror a gateway
 * authorized while the body ran something else is the whole attack, and nothing about it is
 * version-specific: it is wrong on a revision that never asked for the header just as much as on
 * one that demands it. So this is ungated by version, and asks nothing of the negotiated protocol.
 *
 * <p>Which is what lets SEP-2243's own canonical example work: it mirrors
 * {@code Mcp-Method: initialize} on the handshake request, which by construction cannot yet carry a
 * negotiated {@code MCP-Protocol-Version}. That request negotiates a pre-SEP-2243 revision, so no
 * mirror is demanded of it — but the one it sends is still compared to the body.
 *
 * <p>SEP-2243's advice to reject mirrors on an unvalidated version is addressed to intermediaries,
 * which cannot know whether the origin server validates; a server that does validate answers the
 * question the advice works around, so it applies the check rather than the rejection.
 *
 * <p>Requiring a mirror in the first place is the separate, genuinely version-scoped half, left to
 * the revision that adopted SEP-2243: see {@code v2026_07_28.transport.RequestValidationHandler}.
 *
 * <p>One instance per server (constructed with that server's {@link ServerEngine} to resolve
 * {@code x-mcp-header} tool-schema annotations). Runs after {@code http-aggregator} — it needs the
 * parsed body — and after each version's own request validation, so a request malformed for its
 * revision is reported as that rather than as a mirror mismatch. A duplicate mirror field line is a
 * body-independent concern rejected pre-aggregation by {@link McpHeaderGuardHandler}.
 */
@Sharable
@InternalApi
public final class McpMirrorValidationHandler extends ChannelInboundHandlerAdapter {

    /** 2^53-1. Past it a JSON number cannot round-trip through a JavaScript intermediary intact. */
    private static final BigDecimal MAX_SAFE_INTEGER = BigDecimal.valueOf(9007199254740991L);

    private final ServerEngine server;

    public McpMirrorValidationHandler(ServerEngine server) {
        this.server = server;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest req) || req.method() != HttpMethod.POST || !carriesMirror(req)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (ChannelHandlerUtils.getInteractionContext(ctx) == null) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Malformed JSON parses to null: let the normal parse-error path downstream handle it.
        var message = PeekedBody.peek(ctx, req);
        if (message == null) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Mcp-Method mirrors a notification's method too; Mcp-Name/Mcp-Param-* mirror request params,
        // but the character-format rule is about the wire, not the message kind. No id to echo.
        if (message instanceof JsonRpcMessage.Notification<?> notification) {
            var rejection = matchMethod(req, notification.method());
            if (rejection == null) rejection = validateParamHeaderChars(req);
            fireOrReject(ctx, req, null, rejection);
            return;
        }
        if (!(message instanceof JsonRpcMessage.Request<?> request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        fireOrReject(ctx, req, request.id(), validate(req, request));
    }

    private @Nullable ServerError validate(FullHttpRequest req, JsonRpcMessage.Request<?> request) {
        var method = request.method();
        var methodRejection = matchMethod(req, method);
        if (methodRejection != null) return methodRejection;

        var params = JsonUtils.toParamsNode(request.params());
        var nameRejection = matchName(req, method, params);
        if (nameRejection != null) return nameRejection;

        var charRejection = validateParamHeaderChars(req);
        if (charRejection != null) return charRejection;

        for (var argument : MirroredArgument.of(server.tools(), method, params)) {
            var rejection = matchArgument(req, argument);
            if (rejection != null) return rejection;
        }
        return null;
    }

    /** Whether any mirror is present — the cheap skip for the pre-SEP-2243 clients that send none. */
    private static boolean carriesMirror(HttpRequest req) {
        if (req.headers().get(McpHeaderNames.MCP_METHOD) != null
                || req.headers().get(McpHeaderNames.MCP_NAME) != null) {
            return true;
        }
        var it = req.headers().iteratorCharSequence();
        while (it.hasNext()) {
            if (McpHeaderNames.isParamHeader(it.next().getKey())) return true;
        }
        return false;
    }

    private static @Nullable ServerError matchMethod(HttpRequest req, String bodyMethod) {
        var headerMethod = req.headers().get(McpHeaderNames.MCP_METHOD);
        if (headerMethod == null || headerMethod.strip().equals(bodyMethod)) return null;
        return ServerErrors.headerMismatch("Header mismatch: " + McpHeaderNames.MCP_METHOD + " header value '"
                + headerMethod.strip() + "' does not match body method '" + bodyMethod + "'");
    }

    /**
     * Compares {@code Mcp-Name} against the body field it mirrors — {@code params.uri} for
     * {@code resources/read}, {@code params.name} for {@code tools/call} and {@code prompts/get}.
     * A method SEP-2243 gives no mirrored field has nothing to compare the header to, so one sent
     * anyway is left alone: it names no target a gateway could route to.
     */
    private static @Nullable ServerError matchName(HttpRequest req, String method, JsonNode params) {
        var rawHeader = req.headers().get(McpHeaderNames.MCP_NAME);
        var bodyField = McpHeaderNames.mirroredNameField(method);
        if (rawHeader == null || bodyField == null) return null;

        var bodyName = asString(params.get(bodyField));
        var headerName = McpHeaderValue.decodeLenient(rawHeader);
        if (headerName == null || !headerName.equals(bodyName)) {
            return ServerErrors.headerMismatch("Header mismatch: " + McpHeaderNames.MCP_NAME + " header value '"
                    + headerName + "' does not match body value '" + bodyName + "'");
        }
        return null;
    }

    private static @Nullable ServerError matchArgument(HttpRequest req, MirroredArgument argument) {
        var headerName = argument.headerName();
        var rawHeader = req.headers().get(headerName);
        if (rawHeader == null) return null;

        var bodyValue = argument.bodyValue();
        if (bodyValue == null) {
            // A header the body has nothing behind carries a value a gateway would route on and the
            // server would never execute.
            return ServerErrors.headerMismatch(
                    "Header mismatch: " + headerName + " is present but body has no matching value");
        }
        if (argument.value() instanceof Number number) {
            var unmirrorable = unmirrorableNumber(headerName, number, bodyValue);
            if (unmirrorable != null) return unmirrorable;
        }
        String decodedHeader;
        try {
            decodedHeader = McpHeaderValue.decodeStrict(rawHeader);
        } catch (IllegalArgumentException e) {
            return ServerErrors.headerMismatch("Header mismatch: " + headerName + " has invalid Base64 encoding");
        }
        if (!matches(argument.value(), bodyValue, decodedHeader)) {
            return ServerErrors.headerMismatch("Header mismatch: " + headerName + " header value '" + decodedHeader
                    + "' does not match body value '" + bodyValue + "'");
        }
        return null;
    }

    /** Why a numeric body value could not have been faithfully mirrored, or {@code null}. */
    private static @Nullable ServerError unmirrorableNumber(String headerName, Number value, String bodyValue) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            // NaN/Infinity: not a BigDecimal literal. Value not echoed: it is client-controlled.
            return ServerErrors.headerMismatch("Header mismatch: " + headerName + " value is not a finite number");
        }
        return decimal.abs().compareTo(MAX_SAFE_INTEGER) > 0
                ? ServerErrors.headerMismatch("Header mismatch: " + headerName + " value '" + bodyValue
                        + "' is outside the safe integer range and cannot be mirrored")
                : null;
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
     * Not scoped to tools/call, and not to the headers that turn out to mirror something: SEP-2243
     * puts the character restriction on {@code Mcp-Param-*} as a field, matched or not.
     */
    private static @Nullable ServerError validateParamHeaderChars(HttpRequest req) {
        var invalidHeader = findInvalidCharacterParamHeader(req);
        return invalidHeader == null
                ? null
                : ServerErrors.headerMismatch("Header mismatch: " + invalidHeader
                        + " contains characters not permitted in an HTTP field value");
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

    private void fireOrReject(
            ChannelHandlerContext ctx, FullHttpRequest req, @Nullable RequestId id, @Nullable ServerError rejection) {
        if (rejection == null) {
            ctx.fireChannelRead(req);
            return;
        }
        ChannelHandlerUtils.rejectWithServerError(ctx, req, id, rejection);
    }

    private static @Nullable String asString(@Nullable JsonNode value) {
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
