package dev.tachyonmcp.docs.json;

import dev.tachyonmcp.api.json.JsonArray;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class JsonAccess {

    private JsonAccess() {}

    record UserFacts(String name, int age, Optional<JsonObject> address, List<String> roles) {}

    static UserFacts user() {
        // snips-start: json_object_access
        var user = JsonObject.of(Map.of(
            "name", "Ada",
            "age", 32,
            "roles", List.of("admin", "author")
        ));

        String name = user.stringValue("name");
        int age = user.intOr("age", 0);
        Optional<JsonObject> address = user.objectOpt("address");
        List<String> roles = user.arrayValue("roles").valuesAs(String.class);
        // snips-end: json_object_access
        return new UserFacts(name, age, address, roles);
    }

    static double[] coordinates() {
        // snips-start: json_array_access
        var coordinates = JsonArray.of(List.of(59.437, 24.7536));

        double latitude = coordinates.doubleValue(0);
        double longitude = coordinates.doubleValue(1);
        // snips-end: json_array_access
        return new double[] {latitude, longitude};
    }

    static JsonDocument wrap(ObjectMapper objectMapper, String source) {
        // snips-start: json_wrap_node
        JsonNode node = objectMapper.readTree(source);
        JsonDocument document = JsonDocument.from(node, JsonNode.class);
        // snips-end: json_wrap_node
        return document;
    }

    static JsonNode unwrap(JsonDocument document) {
        // snips-start: json_unwrap_node
        JsonNode node = document.unwrap(JsonNode.class).orElseThrow();
        // snips-end: json_unwrap_node
        return node;
    }
}
