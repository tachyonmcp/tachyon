/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions;

import dev.tachyonmcp.api.json.JsonObject;
import java.util.Map;

/** Transport-neutral settings negotiated for a protocol extension. */
@FunctionalInterface
public interface ExtensionSettings {

    /** Returns the settings as an immutable JSON object. */
    JsonObject values();

    /** Creates settings from JSON-compatible values. */
    static ExtensionSettings of(Map<String, ?> values) {
        var object = JsonObject.of(values);
        return () -> object;
    }

    /** Returns empty settings. */
    static ExtensionSettings empty() {
        return JsonObject::empty;
    }
}
