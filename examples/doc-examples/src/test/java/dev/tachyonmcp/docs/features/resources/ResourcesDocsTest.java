package dev.tachyonmcp.docs.features.resources;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpTestServers;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ResourcesDocsTest {

    private static JsonNode firstContents(TachyonServer server, String uri) throws Exception {
        return result(server, "resources/read", "{\"uri\":\"%s\"}".formatted(uri)).path("contents").get(0);
    }

    private static JsonNode firstContents(int port, String uri) throws Exception {
        return result(port, "resources/read", "{\"uri\":\"%s\"}".formatted(uri)).path("contents").get(0);
    }

    @Test
    void annotatedMainServesAFixedResourceAndATemplate() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.features.resources.AnnotatedResourcesServer")) {
            var fixed = items(result(DOCUMENTED_PORT, "resources/list").path("resources")).get(0);
            assertThat(fixed.path("uri").asString()).isEqualTo("app://config");
            assertThat(fixed.path("name").asString()).as("names default to the method name").isEqualTo("config");
            assertThat(fixed.path("mimeType").asString()).isEqualTo("application/json");

            var template = items(result(DOCUMENTED_PORT, "resources/templates/list").path("resourceTemplates")).get(0);
            assertThat(template.path("uriTemplate").asString()).isEqualTo("app://users/{id}");
            assertThat(template.path("name").asString()).isEqualTo("user-profile");

            assertThat(firstContents(DOCUMENTED_PORT, "app://config").path("text").asString())
                    .isEqualTo("{\"environment\":\"production\"}");
            var user = firstContents(DOCUMENTED_PORT, "app://users/42");
            assertThat(user.path("mimeType").asString()).isEqualTo("application/json");
            assertThat(new ObjectMapper().readTree(user.path("text").asString()).path("id").asString())
                    .as("reading app://users/42 binds id to \"42\"")
                    .isEqualTo("42");
        }
    }

    @Test
    void byteArrayResultIsBase64EncodedExactlyOnce() throws Exception {
        try (var server = McpTestServers.start(b -> b.annotations(a -> a.register(new BinaryResources())), s -> {})) {
            var contents = firstContents(server, "app://logo");

            assertThat(contents.path("mimeType").asString()).isEqualTo("image/png");
            assertThat(Base64.getDecoder().decode(contents.path("blob").asString()))
                    .isEqualTo(Files.readAllBytes(Path.of("logo.png")));
        }
    }

    @Test
    void programmaticMainServesTheDescriptorAndHandler() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.features.resources.ProgrammaticResourcesServer")) {
            var fixed = items(result(DOCUMENTED_PORT, "resources/list").path("resources")).get(0);
            assertThat(fixed.path("uri").asString()).isEqualTo("app://config");
            assertThat(fixed.path("description").asString()).isEqualTo("Server configuration");

            var contents = firstContents(DOCUMENTED_PORT, "app://config");
            assertThat(contents.path("uri").asString()).isEqualTo("app://config");
            assertThat(contents.path("text").asString()).isEqualTo("{\"environment\":\"production\"}");
        }
    }

    @Test
    void templateHandlerReceivesTheMatchedVariables() throws Exception {
        try (var server = McpTestServers.start(b -> {}, TemplateResources::register)) {
            var contents = firstContents(server, "app://users/7");

            assertThat(contents.path("uri").asString()).isEqualTo("app://users/7");
            assertThat(contents.path("text").asString()).isEqualTo(TemplateResources.loadUser("7"));
        }
    }

    @Test
    void asyncHandlerCompletesFromTheHttpResponse() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/data", exchange -> {
            var body = "{\"from\":\"upstream\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstream.start();
        try {
            var uri = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/data";
            var descriptor = ResourceDescriptor.builder().name("upstream").uri(uri).build();
            try (var server = McpTestServers.start(
                    b -> {}, s -> AsyncResources.register(s, descriptor, HttpClient.newHttpClient()))) {
                var contents = firstContents(server, uri);

                assertThat(contents.path("text").asString()).isEqualTo("{\"from\":\"upstream\"}");
                assertThat(contents.path("mimeType").asString()).isEqualTo("application/json");
            }
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void blobHandlerReturnsTheRawBytesOnTheWireAsBase64() throws Exception {
        var bytes = new byte[] {1, 2, 3, (byte) 0xff, 0x7f};
        var descriptor = ResourceDescriptor.builder().name("image").uri("app://image").build();
        try (var server = McpTestServers.start(b -> {}, s -> BlobResources.register(s, descriptor, bytes))) {
            var contents = firstContents(server, "app://image");

            assertThat(contents.path("mimeType").asString()).isEqualTo("image/png");
            assertThat(Base64.getDecoder().decode(contents.path("blob").asString())).isEqualTo(bytes);
        }
    }

    @Test
    @Timeout(60)
    void subscribedSessionIsNotifiedWhenTheResourceIsUpdated() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.features.resources.NotifySubscribersServer");
                var client = new Mcp20251125Client(DOCUMENTED_PORT)) {
            var initialize = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-11-25","capabilities":{},
                      "clientInfo":{"name":"test","version":"1"}}}
                    """);
            var capabilities = assertThatResponse(initialize).hasStatus(200).isSuccess().result().path("capabilities");
            assertThat(capabilities.path("resources").path("subscribe").asBoolean()).isTrue();
            client.sendInitialized(initialize.headers().firstValue("MCP-Session-Id").orElseThrow());

            try (var stream = client.openGetStream(null)) {
                stream.awaitFirstEventId(ofSeconds(5));
                assertThatResponse(client.sendRpc("""
                                {"jsonrpc":"2.0","id":2,"method":"resources/subscribe","params":{"uri":"app://config"}}
                                """))
                        .isSuccess();

                var notification = stream.await(
                        f -> f.data().contains("notifications/resources/updated"), ofSeconds(10));

                assertThat(notification.json().path("params").path("uri").asString()).isEqualTo("app://config");
            }
        }
    }
}
