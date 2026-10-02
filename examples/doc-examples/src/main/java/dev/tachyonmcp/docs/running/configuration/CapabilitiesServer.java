package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.api.server.config.Mode;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.FeatureConfig;
import dev.tachyonmcp.core.server.config.ResourcesConfig;

public final class CapabilitiesServer {

    private CapabilitiesServer() {}

    public static void main(String[] args) {
        // snips-start: config_capabilities
        var server = TachyonServer.builder()
            .capabilities(c -> c
                .tools(FeatureConfig.builder().mode(Mode.ON).listChanged(true).build())
                .resources(ResourcesConfig.builder().mode(Mode.ON).subscribe(true).build())
                .completions()
                .logging())
            .port(8080)
            .build();
        server.start();
        // snips-end: config_capabilities
    }
}
