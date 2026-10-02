package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.core.server.TachyonServer;
import java.time.Duration;

public final class ObservabilityServer {

    private ObservabilityServer() {}

    public static void main(String[] args) {
        // snips-start: config_observability
        var server = TachyonServer.builder()
            .observability(o -> o.slowRequestLogging().slowRequestThreshold(Duration.ofSeconds(5)))
            .port(8080)
            .build();
        server.start();
        // snips-end: config_observability
    }
}
