package dev.tachyonmcp.docs.features.resources;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.core.server.TachyonServer;

final class TemplateResources {

    private TemplateResources() {}

    static String loadUser(String id) {
        return "{\"id\":\"" + id + "\",\"displayName\":\"Ada\"}";
    }

    static void register(TachyonServer server) {
        // snips-start: resources_template
        server.resources().registerTemplate(
                template -> template
                        .name("user-profile")
                        .uriTemplate("app://users/{id}")
                        .description("User profile by ID")
                        .mimeType("application/json"),
                (context, request) -> {
                    var id = request.params().get("id").scalarValue();
                    return TextResourceContents.of(
                            request.uri(), loadUser(id), "application/json");
                });
        // snips-end: resources_template
    }
}
