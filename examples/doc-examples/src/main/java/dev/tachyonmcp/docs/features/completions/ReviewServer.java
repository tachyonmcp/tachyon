package dev.tachyonmcp.docs.features.completions;

import dev.tachyonmcp.core.server.TachyonServer;

final class ReviewServer {

    private ReviewServer() {}

    static TachyonServer build() {
        // snips-start: completions_review_server
        var server = TachyonServer.builder()
                .annotations(annotations -> annotations.register(new ReviewService()))
                .port(8080)
                .build();
        // snips-end: completions_review_server
        return server;
    }
}
