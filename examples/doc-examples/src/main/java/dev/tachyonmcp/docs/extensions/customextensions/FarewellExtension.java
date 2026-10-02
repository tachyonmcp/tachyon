package dev.tachyonmcp.docs.extensions.customextensions;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;

public class FarewellExtension implements ServerExtension {

    @Override
    public String extensionId() {
        return "com.example/farewells";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    // snips-start: custom_ext_gated_tool
    @Override
    public void bootstrap(ExtensionContext context) {
        context.tools().register(
                ToolDescriptor.builder()
                        .name("farewell")
                        .description("Says goodbye")
                        .extensionId(extensionId())
                        .build(),
                (interaction, request) -> ToolResult.text("Goodbye!"));
    }
    // snips-end: custom_ext_gated_tool
}
