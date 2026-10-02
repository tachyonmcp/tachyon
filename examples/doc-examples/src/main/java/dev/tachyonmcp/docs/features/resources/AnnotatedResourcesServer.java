package dev.tachyonmcp.docs.features.resources;

import dev.tachyonmcp.core.server.TachyonServer;

public final class AnnotatedResourcesServer {

    private AnnotatedResourcesServer() {}

    public static void main(String[] args) {
        // snips-start: resources_annotated_server
        var server = TachyonServer.builder()
                .annotations(annotations -> annotations.register(new AppResources()))
                .port(8080)
                .build();
        // snips-end: resources_annotated_server
        server.start();
    }
}
