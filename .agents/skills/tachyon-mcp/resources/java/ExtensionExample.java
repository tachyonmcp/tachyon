/*
 * Copyright (c) 2026 Konstantin Pavlov and contributors.
 */

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.Map;

/**
 * Custom SEP-2133 extension: a raw JSON-RPC method plus an extension-gated tool.
 */
final class ExtensionExample {

    static final class AuditExtension implements ServerExtension {

        static final String ID = "com.example/audit";

        @Override
        public String extensionId() {
            return ID;
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.ALWAYS;
        }

        @Override
        public ExtensionSettings serverSettings() {
            return ExtensionSettings.of(Map.of("version", "1.0"));
        }

        @Override
        public void bootstrap(ExtensionContext context) {
            context.registerHandler("com.example/audit-query", (interaction, params) -> {
                var user = params == null ? "anyone" : params.stringOr("user", "anyone");
                return Map.of("user", user, "entries", 0);
            });
            context.tools().register(
                    ToolDescriptor.builder()
                            .name("audit-log")
                            .description("Writes an audit entry")
                            .extensionId(ID)
                            .build(),
                    (interaction, request) -> ToolResult.text("ok"));
        }

        @Override
        public void onConnectionInit(InteractionContext ctx, ExtensionSettings clientSettings) {
            // the client declared this extension
        }
    }

    public static void main(String[] args) {
        var server = TachyonServer.builder()
                .withExtensions(new AuditExtension())
                .session(session -> session.enabled())
                .port(8080)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
