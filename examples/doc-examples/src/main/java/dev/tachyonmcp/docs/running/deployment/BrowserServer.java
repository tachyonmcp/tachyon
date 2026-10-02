package dev.tachyonmcp.docs.running.deployment;

import dev.tachyonmcp.core.server.TachyonServer;

final class BrowserServer {

    private BrowserServer() {}

    static TachyonServer start() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: deploy_browser
                .network(n -> n.allowedHosts("mcp.example.com").allowedOrigins("https://app.example.com"))
                // snips-end: deploy_browser
                .build();
        server.start();
        return server;
    }
}
