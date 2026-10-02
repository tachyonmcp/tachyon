package dev.tachyonmcp.docs.extensions.customextensions;

// snips-start: custom_ext_greetings
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import java.util.Map;

public class GreetingsExtension implements ServerExtension {

    @Override
    public String extensionId() {
        return "com.example/greetings";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    @Override
    public void bootstrap(ExtensionContext context) {
        context.registerHandler("com.example/greet", (interaction, params) -> {
            var name = params == null ? "stranger" : params.stringOr("name", "stranger");
            return Map.of("message", "Hello, " + name + "!");
        });
    }
}
// snips-end: custom_ext_greetings
