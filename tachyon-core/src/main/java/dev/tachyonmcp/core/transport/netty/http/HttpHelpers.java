/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import org.jspecify.annotations.Nullable;

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
     * Set required HTTP headers for SSE stream response.
     *
     * <p>{@code Connection: close} because the server hard-closes the channel when the stream
     * ends ({@code PostSseStream}/{@code NettySseConnection}); advertising keep-alive lets
     * clients pool the socket and race the server's FIN with the next request.
     */
    public static void setSseStreamHeaders(HttpResponse response, @Nullable String origin) {
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
                .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
                .set(X_ACCEL_BUFFERING, "no");
        HttpUtil.setKeepAlive(response, false);
        if (origin != null) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        }
    }
}
