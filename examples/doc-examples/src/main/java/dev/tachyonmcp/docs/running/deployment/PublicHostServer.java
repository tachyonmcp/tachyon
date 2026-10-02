package dev.tachyonmcp.docs.running.deployment;

import dev.tachyonmcp.core.server.TachyonServer;

public final class PublicHostServer {

    private PublicHostServer() {}

    public static void main(String[] args) {
        var server = TachyonServer.builder()
                .port(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")))
                // snips-start: deploy_allowed_host
                .network(n -> {
                    var allowedHost = System.getenv("ALLOWED_HOST");
                    if (allowedHost != null && !allowedHost.isBlank()) {
                        n.allowedHosts(allowedHost);
                    }
                })
                // snips-end: deploy_allowed_host
                .build();
        server.start();
    }
}
