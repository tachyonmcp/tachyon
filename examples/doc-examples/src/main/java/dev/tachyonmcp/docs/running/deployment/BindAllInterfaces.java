package dev.tachyonmcp.docs.running.deployment;

import dev.tachyonmcp.core.server.TachyonServer;

final class BindAllInterfaces {

    private BindAllInterfaces() {}

    static TachyonServer start() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: deploy_bind_all
                .network(n -> n.host("0.0.0.0"))
                // snips-end: deploy_bind_all
                .build();
        server.start();
        return server;
    }
}
