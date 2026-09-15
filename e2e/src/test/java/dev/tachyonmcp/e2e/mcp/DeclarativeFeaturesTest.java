/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Plain Java service exposed with only {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt}
 * through {@code ServerBuilder.annotations(a -> a.register(service))}.
 */
class DeclarativeFeaturesTest {

    private record ForecastRequest(String city, int days) {}

    private record Forecast(String city, int days, double celsius) {}

    private record Station(String id, List<String> sensors) {}

    enum Season {
        SUMMER,
        WINTER
    }

    @SuppressWarnings("unused")
    static class WeatherService {

        @McpTool(description = "Forecast for a city")
        Forecast forecast(ForecastRequest request) {
            return new Forecast(request.city(), request.days(), 21.5);
        }

        @McpTool
        String greet(String name, @Nullable String title, InteractionContext ctx) {
            return "Hello, " + (title == null ? "" : title + " ") + name + "!";
        }

        @McpTool
        void ping() {}

        @McpTool
        int tally(Map<String, Integer> counts) {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        @McpTool
        String fail(String reason) throws IOException {
            throw new IOException(reason);
        }

        @McpResource(uri = "weather://station")
        Station station() {
            return new Station("st-1", List.of("temp", "wind"));
        }

        @McpResource(uri = "weather://cities/{city}", mimeType = "text/plain")
        String city(String city) {
            return "City " + city;
        }

        @McpPrompt(description = "Plan a trip")
        String trip(String city, Optional<String> season) {
            return "Plan a trip to " + city + season.map(s -> " in " + s).orElse("");
        }

        @McpPrompt(role = Role.ASSISTANT)
        List<String> opener(String city) {
            return List.of("Welcome to " + city + "!", "Where should we start?");
        }

        @McpResource(uri = "weather://seasons/{season}", mimeType = "text/plain")
        String season(Season season) {
            return "Season " + season.name();
        }

        @McpPrompt
        String packing(Season season, Optional<Season> next) {
            return "Pack for " + season.name()
                    + next.map(s -> " then " + s.name()).orElse("");
        }

        @McpCompletion(resource = "weather://seasons/{season}")
        List<String> seasons(String season) {
            return List.of("custom:" + season);
        }
    }

    private static TachyonServer server;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(b -> b.annotations(a -> a.register(new WeatherService())), s -> {});
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void toolsListAdvertisesSchemasDerivedFromSignatures() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                      "tools":[
                        {"name":"fail",
                         "inputSchema":{"type":"object","properties":{"reason":{"type":"string"}},"required":["reason"]}},
                        {"name":"forecast","description":"Forecast for a city",
                         "inputSchema":{"type":"object",
                           "properties":{"city":{"type":"string"},"days":{"type":"integer"}},
                           "required":["city","days"]},
                         "outputSchema":{"type":"object",
                           "properties":{"city":{"type":"string"},"days":{"type":"integer"},"celsius":{"type":"number"}},
                           "required":["city","days","celsius"]}},
                        {"name":"greet",
                         "inputSchema":{"type":"object",
                           "properties":{"name":{"type":"string"},"title":{"type":"string"}},
                           "required":["name"]}},
                        {"name":"ping","inputSchema":{"type":"object","properties":{}}},
                        {"name":"tally",
                         "inputSchema":{"type":"object","additionalProperties":{"type":"integer"}}}
                      ],
                      "resultType":"complete","ttlMs":0,"cacheScope":"public"}}
                    """;
            assertThatJson(response.body()).isEqualTo(expected);
        }
    }

    @Test
    void recordInputDecodesWholeArgumentsAndRecordOutputIsStructured() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call",
                     "params":{"name":"forecast","arguments":{"city":"Tallinn","days":3}}}
                    """);

            assertThat(response).isSuccess().hasId(2).hasResult("""
                    {"content":[{"type":"text","text":"{\\"city\\":\\"Tallinn\\",\\"days\\":3,\\"celsius\\":21.5}"}],
                     "structuredContent":{"city":"Tallinn","days":3,"celsius":21.5},
                     "resultType":"complete"}
                    """);
        }
    }

    @Test
    void namedParametersBindNullableAndInjectContext() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var full = client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":"Ada","title":"Dr."}}}
                    """);
            var minimal = client.post("""
                    {"jsonrpc":"2.0","id":4,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":"Ada"}}}
                    """);
            var empty = client.post("""
                    {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ping","arguments":{}}}
                    """);

            assertThat(full).isSuccess().hasId(3).hasResult("""
                    {"content":[{"type":"text","text":"Hello, Dr. Ada!"}],"resultType":"complete"}
                    """);
            assertThat(minimal).isSuccess().hasId(4).hasResult("""
                    {"content":[{"type":"text","text":"Hello, Ada!"}],"resultType":"complete"}
                    """);
            assertThat(empty).isSuccess().hasId(5).hasResult("""
                    {"content":[],"resultType":"complete"}
                    """);
        }
    }

    @Test
    void mapInputKeepsValueSchemaAndDecodesWholeArguments() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":23,"method":"tools/call",
                     "params":{"name":"tally","arguments":{"a":1,"b":2}}}
                    """)).isSuccess().hasId(23).hasResult("""
                    {"content":[{"type":"text","text":"3"}],"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":24,"method":"tools/call",
                     "params":{"name":"tally","arguments":{"a":"one"}}}
                    """)).isJsonRpcError().hasId(24).hasErrorCode(-32602);
        }
    }

    @Test
    void checkedExceptionPropagatesLikeToolFn() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":6,"method":"tools/call",
                     "params":{"name":"fail","arguments":{"reason":"boom"}}}
                    """);

            assertThat(response).isJsonRpcError().hasId(6).hasErrorCode(-32603).hasErrorMessage("Tool handler failed");
        }
    }

    @Test
    void missingRequiredNamedArgumentIsRejected() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"greet","arguments":{}}}
                    """);

            assertThat(response)
                    .isJsonRpcError()
                    .hasId(11)
                    .hasErrorCode(-32602)
                    .hasErrorMessage("required property 'name' not found");
        }
    }

    @Test
    void staticResourceSerializesRecordAsJsonAndTemplateBindsVariable() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var station = client.post("""
                    {"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"weather://station"}}
                    """);
            var city = client.post("""
                    {"jsonrpc":"2.0","id":8,"method":"resources/read","params":{"uri":"weather://cities/Tartu"}}
                    """);

            assertThat(station).isSuccess().hasId(7).hasResult("""
                    {"contents":[{"uri":"weather://station","mimeType":"application/json",
                                  "text":"{\\"id\\":\\"st-1\\",\\"sensors\\":[\\"temp\\",\\"wind\\"]}"}],
                     "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    """);
            assertThat(city).isSuccess().hasId(8).hasResult("""
                    {"contents":[{"uri":"weather://cities/Tartu","mimeType":"text/plain","text":"City Tartu"}],
                     "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    """);
        }
    }

    @Test
    void promptAdvertisesArgumentsAndRendersMessage() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":9,"method":"prompts/list"}
                    """);
            var get = client.post("""
                    {"jsonrpc":"2.0","id":10,"method":"prompts/get",
                     "params":{"name":"trip","arguments":{"city":"Riga","season":"summer"}}}
                    """);

            assertThat(list).isSuccess().hasId(9).hasResult("""
                    {"prompts":[{"name":"opener","arguments":[{"name":"city","required":true}]},
                                {"name":"packing",
                                 "arguments":[{"name":"season","required":true},{"name":"next","required":false}]},
                                {"name":"trip","description":"Plan a trip",
                                 "arguments":[{"name":"city","required":true},{"name":"season","required":false}]}],
                     "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    """);
            assertThat(get).isSuccess().hasId(10).hasResult("""
                    {"description":"Plan a trip",
                     "messages":[{"role":"user","content":{"type":"text","text":"Plan a trip to Riga in summer"}}],
                     "resultType":"complete"}
                    """);
        }
    }

    @Test
    void enumArgumentsBindByConstantName() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":13,"method":"resources/read","params":{"uri":"weather://seasons/WINTER"}}
                    """)).isSuccess().hasId(13).hasResult("""
                    {"contents":[{"uri":"weather://seasons/WINTER","mimeType":"text/plain","text":"Season WINTER"}],
                     "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":14,"method":"prompts/get",
                     "params":{"name":"packing","arguments":{"season":"SUMMER","next":"WINTER"}}}
                    """)).isSuccess().hasId(14).hasResult("""
                    {"messages":[{"role":"user","content":{"type":"text","text":"Pack for SUMMER then WINTER"}}],
                     "resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":15,"method":"prompts/get",
                     "params":{"name":"packing","arguments":{"season":"SUMMER"}}}
                    """)).isSuccess().hasId(15).hasResult("""
                    {"messages":[{"role":"user","content":{"type":"text","text":"Pack for SUMMER"}}],
                     "resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":16,"method":"prompts/get",
                     "params":{"name":"packing","arguments":{"season":"summer"}}}
                    """))
                    .isJsonRpcError()
                    .hasId(16)
                    .hasErrorCode(-32602)
                    .hasErrorMessage("invalid argument 'season': must be one of [SUMMER, WINTER]");
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":17,"method":"resources/read","params":{"uri":"weather://seasons/SPRING"}}
                    """))
                    .isJsonRpcError()
                    .hasId(17)
                    .hasErrorCode(-32602)
                    .hasErrorMessage("invalid argument 'season': must be one of [SUMMER, WINTER]");
        }
    }

    @Test
    void enumArgumentsCompleteFromConstantsUnlessCompletionIsDeclared() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":18,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"packing"},"argument":{"name":"season","value":"s"}}}
                    """)).isSuccess().hasId(18).hasResult("""
                    {"completion":{"values":["SUMMER"]},"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":19,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"packing"},"argument":{"name":"season","value":""}}}
                    """)).isSuccess().hasId(19).hasResult("""
                    {"completion":{"values":["SUMMER","WINTER"]},"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":20,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"packing"},"argument":{"name":"next","value":"Wi"}}}
                    """)).isSuccess().hasId(20).hasResult("""
                    {"completion":{"values":["WINTER"]},"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":21,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"packing"},"argument":{"name":"city","value":"R"}}}
                    """)).isSuccess().hasId(21).hasResult("""
                    {"completion":{"values":[],"hasMore":false},"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":22,"method":"completion/complete","params":{
                     "ref":{"type":"ref/resource","uri":"weather://seasons/{season}"},"argument":{"name":"season","value":"wi"}}}
                    """)).isSuccess().hasId(22).hasResult("""
                    {"completion":{"values":["custom:wi"]},"resultType":"complete"}
                    """);
        }
    }

    @Test
    void promptRoleAppliesToEveryRenderedMessage() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var get = client.post("""
                    {"jsonrpc":"2.0","id":12,"method":"prompts/get",
                     "params":{"name":"opener","arguments":{"city":"Riga"}}}
                    """);

            assertThat(get).isSuccess().hasId(12).hasResult("""
                    {"messages":[
                       {"role":"assistant","content":{"type":"text","text":"Welcome to Riga!"}},
                       {"role":"assistant","content":{"type":"text","text":"Where should we start?"}}],
                     "resultType":"complete"}
                    """);
        }
    }
}
