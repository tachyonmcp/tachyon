package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.core.server.TachyonServer;

final class TypedForecastTool {

    private TypedForecastTool() {}

    // snips-start: tools_typed_records
    record ForecastRequest(String city, int days) {}
    record Forecast(String summary, double highC) {}
    // snips-end: tools_typed_records

    static String lookup(String city) {
        return "Sunny in " + city;
    }

    static double highFor(String city, int days) {
        return 20.0 + days;
    }

    static TachyonServer start() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: tools_typed_register
                .withTools(tools -> tools.register(
                        ForecastRequest.class,
                        Forecast.class,
                        tool -> tool.name("get_forecast").description("Multi-day forecast"),
                        (ctx, input) -> new Forecast(lookup(input.city()), highFor(input.city(), input.days()))))
                // snips-end: tools_typed_register
                .build();
        server.start();
        return server;
    }
}
