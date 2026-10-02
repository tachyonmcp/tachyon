package dev.tachyonmcp.docs.features.clientinteractions;

import dev.tachyonmcp.core.server.TachyonServer;

final class CityServer {

    private CityServer() {}

    static TachyonServer build() {
        // snips-start: ci_city_server
        var server = TachyonServer.builder()
                .session(session -> session.enabled())
                .annotations(annotations -> annotations.register(new CityTools()))
                .port(8080)
                .build();
        // snips-end: ci_city_server
        return server;
    }
}
