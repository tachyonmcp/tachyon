/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeclarativeCompletionsTest {
    static class Service {
        @McpCompletion(prompt = "trip")
        CompletionResult trip(CompletionRequest request, InteractionContext context) {
            assertThat(context).isNotNull();
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            return CompletionResult.builder()
                    .values(List.of(
                            request.argumentName() + ":" + request.argumentValue(),
                            request.resolvedArguments().get("country")))
                    .total(10)
                    .hasMore(true)
                    .meta(Map.of("source", "test"))
                    .build();
        }

        @McpCompletion(resource = "weather://{city}")
        List<String> resource(CompletionRequest request) {
            return List.of(request.argumentValue() + "ville");
        }

        @McpCompletion(prompt = "failure")
        CompletionResult failure(CompletionRequest request) throws IOException {
            throw new IOException("private backend detail");
        }

        @McpCompletion(prompt = "cities")
        List<String> cities(
                InteractionContext context, String city, @Nullable String country, @Nullable Integer limit) {
            assertThat(context).isNotNull();
            return List.of(city + ":" + (country == null ? "anywhere" : country) + ":" + (limit == null ? 5 : limit));
        }

        @McpCompletion(resource = "places://{country}/{city}")
        List<String> places(String city, String country) {
            return List.of(city + ", " + country);
        }

        @McpCompletion(prompt = "null-result")
        @Nullable
        List<String> nullResult(String city) {
            return null;
        }
    }

    private static TachyonServer server;

    @BeforeAll
    static void start() {
        server = McpTestServers.start(
                builder -> builder.annotations(annotations -> annotations.register(new Service())), ignored -> {});
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2026-07-28"})
    void explicitRequestPreservesContextAndCompleteResult(String version) throws Exception {
        try (var client = McpTestClients.forVersion(server.port(), version)) {
            if (version.equals("2025-11-25")) client.initialize();
            // language=json
            final var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":20,"method":"completion/complete","params":{
                      "ref":{"type":"ref/prompt","name":"trip"},
                      "argument":{"name":"city","value":"Ri"},
                      "context":{"arguments":{"country":"Latvia"}}}}
                    """);
            // language=json
            assertThat(response).isSuccess().hasId(20).hasResult("""
                    {"completion":{"values":["city:Ri","Latvia"],"total":10,"hasMore":true},
                     "_meta":{"source":"test"}%s}
                    """.formatted(resultType(version)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2026-07-28"})
    void resourceListShorthandAndCheckedFailures(String version) throws Exception {
        try (var client = McpTestClients.forVersion(server.port(), version)) {
            if (version.equals("2025-11-25")) client.initialize();
            // language=json
            assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":21,"method":"completion/complete","params":{
                      "ref":{"type":"ref/resource","uri":"weather://{city}"},
                      "argument":{"name":"city","value":"Spring"}}}
                    """)).isSuccess().hasId(21).hasResult("""
                    {"completion":{"values":["Springville"]}%s}
                    """.formatted(resultType(version)));
            // language=json
            assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":22,"method":"completion/complete","params":{
                      "ref":{"type":"ref/prompt","name":"failure"},
                      "argument":{"name":"city","value":"Ri"}}}
                    """))
                    .isJsonRpcError()
                    .hasId(22)
                    .hasErrorCode(-32603)
                    .hasErrorMessage("Completion handler failed");
        }
    }

    private static String resultType(String version) {
        return version.equals("2026-07-28") ? ",\"resultType\":\"complete\"" : "";
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2026-07-28"})
    void namedParametersBindPartialTextAndOptionalSiblings(String version) throws Exception {
        try (var client = McpTestClients.forVersion(server.port(), version)) {
            if (version.equals("2025-11-25")) client.initialize();
            // language=json
            assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":30,"method":"completion/complete","params":{
                      "ref":{"type":"ref/prompt","name":"cities"},
                      "argument":{"name":"city","value":"Ri"},
                      "context":{"arguments":{"city":"stale","country":"Latvia","limit":"2"}}}}
                    """)).isSuccess().hasId(30).hasResult("""
                    {"completion":{"values":["Ri:Latvia:2"]}%s}
                    """.formatted(resultType(version)));
            // language=json
            assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":31,"method":"completion/complete","params":{
                      "ref":{"type":"ref/prompt","name":"cities"},
                      "argument":{"name":"city","value":""}}}
                    """)).isSuccess().hasId(31).hasResult("""
                    {"completion":{"values":[":anywhere:5"]}%s}
                    """.formatted(resultType(version)));
            // language=json
            assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":32,"method":"completion/complete","params":{
                      "ref":{"type":"ref/prompt","name":"cities"},
                      "argument":{"name":"country","value":"La"}}}
                    """)).isSuccess().hasId(32).hasResult("""
                    {"completion":{"values":[],"hasMore":false}%s}
                    """.formatted(resultType(version)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2026-07-28"})
    void namedResourceBindingRejectsMissingRequiredSibling(String version) throws Exception {
        try (var client = McpTestClients.forVersion(server.port(), version)) {
            if (version.equals("2025-11-25")) client.initialize();
            // language=json
            assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":33,"method":"completion/complete","params":{
                      "ref":{"type":"ref/resource","uri":"places://{country}/{city}"},
                      "argument":{"name":"city","value":"Ri"},
                      "context":{"arguments":{"country":"Latvia"}}}}
                    """)).isSuccess().hasId(33).hasResult("""
                    {"completion":{"values":["Ri, Latvia"]}%s}
                    """.formatted(resultType(version)));
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":34,"method":"completion/complete","params":{
                      "ref":{"type":"ref/resource","uri":"places://{country}/{city}"},
                      "argument":{"name":"city","value":"Ri"}}}
                    """))
                    .isJsonRpcError()
                    .hasId(34)
                    .hasErrorCode(-32602)
                    .hasErrorMessage("invalid argument 'country': is required");
            // language=json
            assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":35,"method":"completion/complete","params":{
                      "ref":{"type":"ref/prompt","name":"null-result"},
                      "argument":{"name":"city","value":"Ri"}}}
                    """))
                    .isJsonRpcError()
                    .hasId(35)
                    .hasErrorCode(-32603)
                    .hasErrorMessage("Completion handler failed");
        }
    }
}
