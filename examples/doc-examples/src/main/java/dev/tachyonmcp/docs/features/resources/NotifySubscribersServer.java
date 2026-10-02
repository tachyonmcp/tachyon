package dev.tachyonmcp.docs.features.resources;

import dev.tachyonmcp.core.server.TachyonServer;

public final class NotifySubscribersServer {

    private NotifySubscribersServer() {}

    public static void main(String[] args) {
        // snips-start: resources_notify
        var server = TachyonServer.builder()
                .session(session -> session.enabled())
                .capabilities(capabilities -> capabilities.resources(true, true))
                .annotations(annotations -> annotations.register(new AppResources()))
                .port(8080)
                .build();

        server.resources().notifyResourceUpdated("app://config");
        // snips-end: resources_notify
        server.start();
        Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                server.resources().notifyResourceUpdated("app://config");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }
}
