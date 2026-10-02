package dev.tachyonmcp.docs.features.prompts;

import dev.tachyonmcp.core.server.TachyonServer;

public final class AnnotatedPromptsServer {

    private AnnotatedPromptsServer() {}

    public static void main(String[] args) {
        // snips-start: prompts_annotated_server
        var server = TachyonServer.builder()
                .annotations(annotations -> annotations.register(new ReviewPrompts()))
                .port(8080)
                .build();
        // snips-end: prompts_annotated_server
        server.start();
    }
}
