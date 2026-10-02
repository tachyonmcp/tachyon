package dev.tachyonmcp.docs.features.resources;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.core.server.TachyonServer;

public final class ProgrammaticResourcesServer {

    private ProgrammaticResourcesServer() {}

    public static void main(String[] args) {
        // snips-start: resources_programmatic_server
        var server = TachyonServer.builder()
                .withResources(resources -> resources.register(
                        descriptor -> descriptor
                                .name("config")
                                .uri("app://config")
                                .description("Server configuration")
                                .mimeType("application/json"),
                        (context, request) -> TextResourceContents.of(
                                request.uri(),
                                "{\"environment\":\"production\"}",
                                "application/json")))
                .port(8080)
                .build();
        // snips-end: resources_programmatic_server
        server.start();
    }
}
