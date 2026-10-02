package dev.tachyonmcp.docs.features.resources;

import dev.tachyonmcp.api.server.domain.BlobResourceContents;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.core.server.TachyonServer;

final class BlobResources {

    private BlobResources() {}

    static void register(TachyonServer server, ResourceDescriptor descriptor, byte[] imageBytes) {
        server.resources().register(
                descriptor,
                // snips-start: resources_blob
                (context, request) -> BlobResourceContents.of(request.uri(), imageBytes, "image/png")
                // snips-end: resources_blob
                );
    }
}
