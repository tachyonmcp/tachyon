/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.cors.CorsConfig;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The CORS response headers one request earns, decided once from that request and carried to every
 * response written for it — including a response written long after, by an asynchronous handler.
 * Netty's {@code CorsHandler} decides at write time from the request its channel read <em>last</em>,
 * which on a keep-alive connection may be a different request; see {@link TachyonCorsHandler}.
 *
 * @param allowOrigin      the {@code Access-Control-Allow-Origin} value, or {@code null} for no grant
 * @param varyOrigin       whether the response depends on {@code Origin} and needs {@code Vary: Origin}
 * @param exposeHeaders    {@code Access-Control-Expose-Headers} values, applied with a grant
 * @param allowCredentials whether to send {@code Access-Control-Allow-Credentials: true} with a grant
 */
@InternalApi
public record CorsDecision(
        @Nullable String allowOrigin, boolean varyOrigin, Set<String> exposeHeaders, boolean allowCredentials) {

    private static final String ANY_ORIGIN = "*";

    /** No CORS headers at all: the request carried no {@code Origin}, or CORS is not managed. */
    public static final CorsDecision NONE = new CorsDecision(null, false, Set.of(), false);

    public CorsDecision {
        exposeHeaders = Set.copyOf(exposeHeaders);
    }

    /**
     * Decides the CORS headers for {@code request}, with the semantics of Netty's {@code CorsHandler}
     * for a single {@code CorsConfig}, plus one deliberate difference: with a finite origin list, every
     * request carrying {@code Origin} gets {@code Vary: Origin}, grant or not, since the response then
     * depends on the origin. A listed origin matches in its {@linkplain Origins#canonical canonical} form; the grant echoes the request's own value.
     *
     * @param config  the CORS configuration
     * @param request the request being answered
     * @return the decision for every response to {@code request}
     */
    public static CorsDecision of(CorsConfig config, HttpRequest request) {
        var origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin == null || !config.isCorsSupportEnabled()) {
            return NONE;
        }
        var credentials = config.isCredentialsAllowed();
        var exposed = config.exposedHeaders();
        var list = !config.isAnyOriginSupported();
        var canonical = Origins.canonical(origin);
        if (canonical == null) {
            return new CorsDecision(null, list, Set.of(), false);
        }
        if (!list) {
            return credentials
                    ? new CorsDecision(origin, true, exposed, true)
                    : new CorsDecision(ANY_ORIGIN, false, exposed, false);
        }
        if (config.origins().contains(canonical)) {
            return new CorsDecision(origin, true, exposed, credentials);
        }
        return new CorsDecision(null, true, Set.of(), false);
    }

    /**
     * Adds this decision's headers to {@code response}. {@code Vary: Origin} is added once, keeping any
     * other {@code Vary} value.
     *
     * @param response the response to decorate
     */
    public void applyTo(HttpResponse response) {
        var headers = response.headers();
        if (varyOrigin && !headers.containsValue(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN, true)) {
            headers.add(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
        }
        if (allowOrigin == null) {
            return;
        }
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowOrigin);
        if (allowCredentials && !ANY_ORIGIN.equals(allowOrigin)) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
        if (!exposeHeaders.isEmpty()) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, exposeHeaders);
        }
    }
}
