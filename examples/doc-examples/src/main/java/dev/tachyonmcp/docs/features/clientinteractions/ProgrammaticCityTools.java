package dev.tachyonmcp.docs.features.clientinteractions;

import dev.tachyonmcp.core.server.TachyonServer;

final class ProgrammaticCityTools {

    private ProgrammaticCityTools() {}

    static void register(TachyonServer server) {
        // snips-start: ci_programmatic
        var cityTools = new CityTools();
        server.tools().register(
                tool -> tool.name("choose-city").description("Ask the user to choose a forecast city"),
                (context, request) -> cityTools.chooseCity(context));
        // snips-end: ci_programmatic
    }
}
