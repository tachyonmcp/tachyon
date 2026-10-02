package dev.tachyonmcp.docs.features;

import dev.tachyonmcp.core.server.TachyonServer;

final class FeaturesServer {

    private FeaturesServer() {}

    static TachyonServer build() {
        // snips-start: features_register_annotations
        var server = TachyonServer.builder()
                .annotations(annotations -> annotations.register(new GreetingService()))
                .port(8080)
                .build();
        // snips-end: features_register_annotations
        return server;
    }
}
