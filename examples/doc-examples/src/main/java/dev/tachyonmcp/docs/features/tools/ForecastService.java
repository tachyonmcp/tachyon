package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.api.annotations.McpTool;

final class ForecastService {

    // snips-start: tools_forecast
    public record ForecastRequest(String city, int days) {}
    public record Forecast(String city, int days, double highC) {}

    @McpTool(description = "Multi-day forecast")
    public Forecast forecast(ForecastRequest request) {
        return new Forecast(request.city(), request.days(), 24.0);
    }
    // snips-end: tools_forecast
}
