package dev.tachyonmcp.docs.features.resources;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.core.server.TachyonServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;

final class AsyncResources {

    private AsyncResources() {}

    static void register(TachyonServer server, ResourceDescriptor descriptor, HttpClient httpClient) {
        // snips-start: resources_async
        server.resources().registerAsync(
                descriptor,
                (context, request) -> httpClient.sendAsync(
                                HttpRequest.newBuilder(URI.create(request.uri())).GET().build(),
                                BodyHandlers.ofString())
                        .thenApply(response -> TextResourceContents.of(
                                request.uri(), response.body(), "application/json")));
        // snips-end: resources_async
    }
}
