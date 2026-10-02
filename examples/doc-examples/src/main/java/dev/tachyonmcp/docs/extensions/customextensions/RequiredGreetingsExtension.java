package dev.tachyonmcp.docs.extensions.customextensions;

import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;

public class RequiredGreetingsExtension extends GreetingsExtension {

    // snips-start: custom_ext_required
    @Override
    public ExtensionNegotiation negotiation() {
        return ExtensionNegotiation.REQUIRED;
    }
    // snips-end: custom_ext_required
}
