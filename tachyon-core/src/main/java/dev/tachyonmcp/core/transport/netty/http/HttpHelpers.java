/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;

@InternalApi
public final class HttpHelpers {

    /**
     * Add {@code X-Accel-Buffering: no} to SSE HTTP responses. This instructs reverse proxies (such as
     * nginx) to disable response buffering, ensuring that SSE events are delivered to clients
     * immediately rather than being held in a buffer.
     *
     * @see <a
     * href="https://modelcontextprotocol.io/specification/draft/basic/transports/streamable-http#receiving-messages">
     * MCP Streamable HTTP Protocol</a>
     */
    private static final String X_ACCEL_BUFFERING = "X-Accel-Buffering";

    private HttpHelpers() {}

    /**
     * Returns whether {@code text} is an RFC 9110 §5.6.2 {@code token}: one or more {@code tchar}.
     * The empty string is not a token.
     *
     * @param text the candidate
     * @return whether it is a token
     */
    public static boolean isToken(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final boolean tchar = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tchar) {
                return false;
            }
        }
        return true;
    }

    /**
     * Set required HTTP headers for SSE stream response.
     *
     * <p>{@code Connection: close} because the server hard-closes the channel when the stream
     * ends ({@code PostSseStream}/{@code NettySseConnection}); advertising keep-alive lets
     * clients pool the socket and race the server's FIN with the next request.
     */
    public static void setSseStreamHeaders(HttpResponse response, CorsDecision cors) {
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
                .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
                .set(X_ACCEL_BUFFERING, "no");
        HttpUtil.setKeepAlive(response, false);
        cors.applyTo(response);
    }
}
