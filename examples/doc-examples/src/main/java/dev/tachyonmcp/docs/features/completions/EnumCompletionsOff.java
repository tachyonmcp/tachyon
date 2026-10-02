package dev.tachyonmcp.docs.features.completions;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;

final class EnumCompletionsOff {

    private EnumCompletionsOff() {}

    static TachyonServer build() {
        // snips-start: completions_enum_off
        var server = TachyonServer.builder()
            .annotations(a -> a
                .withProvider(TachyonAnnotationProvider.withEnumCompletions(false))
                .register(new ReviewService()))
            .build();
        // snips-end: completions_enum_off
        return server;
    }
}
