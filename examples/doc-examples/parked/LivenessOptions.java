package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.core.server.TachyonServer;

final class LivenessOptions {

    private LivenessOptions() {}

    static TachyonServer withLiveness() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: config_liveness
                .network(n -> n.liveness()) // or n.liveness("/health/live")
                // snips-end: config_liveness
                .build();
        server.start();
        return server;
    }
}
