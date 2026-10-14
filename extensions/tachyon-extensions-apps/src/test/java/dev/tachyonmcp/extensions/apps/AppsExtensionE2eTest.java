/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.apps;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.apps.AppMeta.Permission;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * End-to-end test of the SEP-1865 apps extension: {@code ui://} resources listed and read with
 * {@code _meta.ui}, tools linked through {@code _meta.ui.resourceUri}, and the extension advertised
 * on both MCP revisions.
 */
class AppsExtensionE2eTest {

    private static final String DASHBOARD_URI = "ui://weather/dashboard";
    private static final String PLAIN_URI = "ui://weather/plain";
    private static final String HTML = "<!DOCTYPE html><html><body><h1>Weather</h1></body></html>";

    private static final AppResource DASHBOARD = AppResource.builder()
            .uri(DASHBOARD_URI)
            .name("weather_dashboard")
            .title("Weather Dashboard")
            .description("Interactive weather dashboard view")
            .html(HTML)
            .meta(AppMeta.builder()
                    .connectDomains("https://api.openweathermap.org")
                    .resourceDomains("https://cdn.jsdelivr.net", "https://*.cloudflare.com")
                    .frameDomains("https://www.youtube.com")
                    .baseUriDomains("https://cdn.example.com")
                    .permissions(Permission.GEOLOCATION, Permission.CLIPBOARD_WRITE)
                    .domain("a904794854a047f6.claudemcpcontent.com")
                    .prefersBorder(true)
                    .build())
            .build();

    private static final AppResource PLAIN =
            AppResource.builder().uri(PLAIN_URI).name("plain").html(() -> HTML).build();

    private static TachyonServer startServer(Consumer<AppsExtension.Builder> apps) {
        return McpTestServers.start(builder -> builder.withExtension(AppsExtension.class, apps), server -> {
            server.tools()
                    .register(
                            tool -> tool.name("get_weather")
                                    .description("Get current weather")
                                    .meta(AppsExtension.toolMeta(
                                            DASHBOARD_URI, UiToolVisibility.MODEL, UiToolVisibility.APP)),
                            (context, request) -> ToolResult.text("Sunny, 72F"));
            server.tools()
                    .register(
                            tool -> tool.name("refresh_dashboard")
                                    .meta(AppsExtension.toolMeta(DASHBOARD_URI, UiToolVisibility.APP)),
                            (context, request) -> ToolResult.text("refreshed"));
            server.tools()
                    .register(
                            tool -> tool.name("default_visibility").meta(AppsExtension.toolMeta(DASHBOARD_URI)),
                            (context, request) -> ToolResult.text("ok"));
        });
    }

    private static Mcp20260728Client createClient(int port) {
        var settings = JsonNodeFactory.instance.objectNode();
        settings.putArray("mimeTypes").add(AppsExtension.MIME_TYPE);
        return new Mcp20260728Client(port).withExtensions(Map.of(AppsExtension.ID, settings));
    }

    @Test
    void advertisesExtensionOnDiscover() throws Exception {
        try (var server = startServer(apps -> apps.app(DASHBOARD));
                var client = createClient(server.port())) {
            client.discover().isSuccess().hasCapabilities("""
                {"tools":{},"resources":{},"extensions":{"io.modelcontextprotocol/ui":{}}}
                """);
        }
    }

    @Test
    void advertisesExtensionOnLegacyInitialize() throws Exception {
        try (var server = startServer(apps -> apps.app(DASHBOARD));
                var client = new Mcp20251125Client(server.port())) {
            var initialize = client.post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25",
                  "capabilities":{"extensions":{"io.modelcontextprotocol/ui":{"mimeTypes":["text/html;profile=mcp-app"]}}},
                  "clientInfo":{"name":"claude-desktop","version":"1.0.0"}}}
                """);
            assertThatJson(initialize.body())
                    .node("result.capabilities.extensions")
                    .isEqualTo("""
                        {"io.modelcontextprotocol/ui":{}}
                        """);
        }
    }

    @Test
    void listsUiResourcesWithMetaAndMimeType() throws Exception {
        try (var server = startServer(apps -> apps.app(DASHBOARD).app(PLAIN));
                var client = createClient(server.port())) {
            var list = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}
                """);
            // language=JSON
            assertThatJson(list.body()).node("result.resources").isEqualTo("""
                [
                  {
                    "uri":"ui://weather/plain",
                    "name":"plain",
                    "mimeType":"text/html;profile=mcp-app"
                  },
                  {
                    "uri":"ui://weather/dashboard",
                    "name":"weather_dashboard",
                    "title":"Weather Dashboard",
                    "description":"Interactive weather dashboard view",
                    "mimeType":"text/html;profile=mcp-app",
                    "_meta":{
                      "ui":{
                        "csp":{
                          "connectDomains":["https://api.openweathermap.org"],
                          "resourceDomains":["https://cdn.jsdelivr.net","https://*.cloudflare.com"],
                          "frameDomains":["https://www.youtube.com"],
                          "baseUriDomains":["https://cdn.example.com"]
                        },
                        "permissions":{"geolocation":{},"clipboardWrite":{}},
                        "domain":"a904794854a047f6.claudemcpcontent.com",
                        "prefersBorder":true
                      }
                    }
                  }
                ]
                """);
        }
    }

    @Test
    void readsUiResourceAsHtmlWithMeta() throws Exception {
        try (var server = startServer(apps -> apps.app(DASHBOARD).app(PLAIN));
                var client = createClient(server.port())) {
            var html = new ObjectMapper().writeValueAsString(HTML);

            var dashboard = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"ui://weather/dashboard"}}
                """);
            // language=JSON
            assertThatJson(dashboard.body()).node("result.contents").isEqualTo("""
                [{
                  "uri":"ui://weather/dashboard",
                  "mimeType":"text/html;profile=mcp-app",
                  "text":%s,
                  "_meta":{
                    "ui":{
                      "csp":{
                        "connectDomains":["https://api.openweathermap.org"],
                        "resourceDomains":["https://cdn.jsdelivr.net","https://*.cloudflare.com"],
                        "frameDomains":["https://www.youtube.com"],
                        "baseUriDomains":["https://cdn.example.com"]
                      },
                      "permissions":{"geolocation":{},"clipboardWrite":{}},
                      "domain":"a904794854a047f6.claudemcpcontent.com",
                      "prefersBorder":true
                    }
                  }
                }]
                """.formatted(html));

            var plain = client.sendRpc("""
                {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"ui://weather/plain"}}
                """);
            // language=JSON
            assertThatJson(plain.body()).node("result.contents").isEqualTo("""
                [{"uri":"ui://weather/plain","mimeType":"text/html;profile=mcp-app","text":%s}]
                """.formatted(html));
        }
    }

    @Test
    void toolsCarryUiMetaWithoutDeprecatedFlatKey() throws Exception {
        try (var server = startServer(apps -> apps.app(DASHBOARD));
                var client = createClient(server.port())) {
            var list = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """);
            // language=JSON
            assertThat(list).isSuccess().hasId(1).hasResult("""
                {
                  "resultType": "complete",
                  "cacheScope": "public",
                  "ttlMs": 0,
                  "tools": [
                    {
                      "name": "default_visibility",
                      "inputSchema": {"type": "object"},
                      "_meta": {"ui": {"resourceUri": "ui://weather/dashboard"}}
                    },
                    {
                      "name": "get_weather",
                      "description": "Get current weather",
                      "inputSchema": {"type": "object"},
                      "_meta": {"ui": {"resourceUri": "ui://weather/dashboard", "visibility": ["model", "app"]}}
                    },
                    {
                      "name": "refresh_dashboard",
                      "inputSchema": {"type": "object"},
                      "_meta": {"ui": {"resourceUri": "ui://weather/dashboard", "visibility": ["app"]}}
                    }
                  ]
                }
                """);
        }
    }

    @Test
    void undeclaredClientStillReadsUiResource() throws Exception {
        try (var server = startServer(apps -> apps.app(PLAIN));
                var client = new Mcp20260728Client(server.port())) {
            var read = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"ui://weather/plain"}}
                """);
            // language=JSON
            assertThatJson(read.body()).node("result.contents").isEqualTo("""
                [{"uri":"ui://weather/plain","mimeType":"text/html;profile=mcp-app","text":%s}]
                """.formatted(
                            new ObjectMapper().writeValueAsString(HTML)));
        }
    }
}
