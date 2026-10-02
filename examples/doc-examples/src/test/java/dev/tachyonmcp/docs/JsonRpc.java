package dev.tachyonmcp.docs;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class JsonRpc {

    private JsonRpc() {}

    public static HttpResponse<String> post(TachyonServer server, String method, String params) throws Exception {
        return post(server.port(), method, params);
    }

    public static HttpResponse<String> post(int port, String method, String params) throws Exception {
        try (var client = McpTestClients.latest(port)) {
            return client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"%s","params":%s}
                    """.formatted(method, params));
        }
    }

    public static JsonNode result(TachyonServer server, String method, String params) throws Exception {
        return result(server.port(), method, params);
    }

    public static JsonNode result(int port, String method, String params) throws Exception {
        return assertThatResponse(post(port, method, params)).hasStatus(200).isSuccess().result();
    }

    public static JsonNode result(int port, String method) throws Exception {
        return result(port, method, "{}");
    }

    public static JsonNode result(TachyonServer server, String method) throws Exception {
        return result(server, method, "{}");
    }

    public static String request(String method, String params) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"%s","params":%s}
                """.formatted(method, params);
    }

    public static Mcp20260728Client declaring(int port, String extensionId) {
        return declaring(port, extensionId, "{}");
    }

    public static Mcp20260728Client declaring(int port, String extensionId, String settingsJson) {
        var settings = new ObjectMapper().readTree(settingsJson);
        return McpTestClients.latest(port).withExtensions(Map.of(extensionId, settings));
    }

    public static List<JsonNode> items(JsonNode array) {
        var items = new ArrayList<JsonNode>();
        array.forEach(items::add);
        return items;
    }

    public static List<String> names(JsonNode result, String key) {
        return items(result.path(key)).stream().map(n -> n.path("name").asString()).toList();
    }

    public static JsonNode named(JsonNode result, String key, String name) {
        return items(result.path(key)).stream()
                .filter(n -> n.path("name").asString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " in " + result.path(key)));
    }

    public static String text(JsonNode callResult) {
        return callResult.path("content").get(0).path("text").asString();
    }
}
