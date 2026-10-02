package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.core.server.TachyonServer;

public final class GreetingServer {

    private GreetingServer() {}

    public static void main(String[] args) {
        // snips-start: tools_greeting_server
        var server = TachyonServer.builder()
                .annotations(annotations -> annotations.register(new GreetingService()))
                .port(8080)
                .build();
        // snips-end: tools_greeting_server
        server.start();
    }
}
