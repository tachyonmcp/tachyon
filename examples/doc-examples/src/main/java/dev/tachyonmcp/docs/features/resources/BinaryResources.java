package dev.tachyonmcp.docs.features.resources;

import dev.tachyonmcp.api.annotations.McpResource;

final class BinaryResources {

    // snips-start: resources_logo
    @McpResource(uri = "app://logo", mimeType = "image/png")
    public byte[] logo() throws java.io.IOException {
        return java.nio.file.Files.readAllBytes(java.nio.file.Path.of("logo.png"));
    }
    // snips-end: resources_logo
}
