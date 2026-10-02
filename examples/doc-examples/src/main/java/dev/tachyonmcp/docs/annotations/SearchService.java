package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.api.annotations.McpParam;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.annotations.Meta;

final class SearchService {
    // snips-start: annotations_param_and_meta
    record TenantMeta(String tenant) {}

    @McpTool
    String search(
            @McpParam(name = "query", description = "Search text") String text,
            @Meta TenantMeta metadata) {
        return metadata.tenant() + ":" + text;
    }
    // snips-end: annotations_param_and_meta
}
