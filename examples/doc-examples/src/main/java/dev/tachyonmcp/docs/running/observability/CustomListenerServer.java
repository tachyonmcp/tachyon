package dev.tachyonmcp.docs.running.observability;

import dev.tachyonmcp.core.server.TachyonServer;

public final class CustomListenerServer {

    private CustomListenerServer() {}

    public static void main(String[] args) {
        var myListener = new RecordingListener();
        // snips-start: otel_custom_listener
        var server = TachyonServer.builder()
            .observability(o -> o.listener(myListener))
            .port(8080)
            .build();
        // snips-end: otel_custom_listener
        server.start();
    }
}
