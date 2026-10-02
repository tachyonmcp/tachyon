package dev.tachyonmcp.docs.running.deployment;

import dev.tachyonmcp.core.server.TachyonServer;

public final class PortFromEnvServer {

    private PortFromEnvServer() {}

    public static void main(String[] args) {
        var server = TachyonServer.builder()
                // snips-start: deploy_port_env
                .network(n -> n.port(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"))))
                // snips-end: deploy_port_env
                .build();
        server.start();
    }
}
