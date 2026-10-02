package dev.tachyonmcp.docs.extensions.customextensions;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import java.util.Map;

public class SettingsExtension implements ServerExtension {

    @Override
    public String extensionId() {
        return "com.example/greetings";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    // snips-start: custom_ext_server_settings
    @Override
    public ExtensionSettings serverSettings() {
        return ExtensionSettings.of(Map.of("version", "1.0"));
    }
    // snips-end: custom_ext_server_settings

    // snips-start: custom_ext_client_settings
    @Override
    public void onConnectionInit(InteractionContext interaction, ExtensionSettings clientSettings) {
        var language = clientSettings.values().stringOr("language", "en");
    }
    // snips-end: custom_ext_client_settings
}
