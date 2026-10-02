package dev.tachyonmcp.docs.features.resources;

// snips-start: resources_app_resources
import dev.tachyonmcp.api.annotations.McpResource;

class AppResources {
    public record UserProfile(String id, String displayName) {}

    @McpResource(uri = "app://config", description = "Server configuration",
            mimeType = "application/json")
    public String config() {
        return "{\"environment\":\"production\"}";
    }

    @McpResource(name = "user-profile", uri = "app://users/{id}",
            description = "User profile by ID")
    public UserProfile user(String id) {
        return new UserProfile(id, "Ada");
    }
}
// snips-end: resources_app_resources
