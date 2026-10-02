package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.core.server.TachyonServer;

final class WeatherServer {

    private WeatherServer() {}

    static TachyonServer build() {
        // snips-start: annotations_weather_server
        var server = TachyonServer.builder()
            .annotations(a -> a.register(new WeatherService()))
            .build();
        // snips-end: annotations_weather_server
        return server;
    }
}
