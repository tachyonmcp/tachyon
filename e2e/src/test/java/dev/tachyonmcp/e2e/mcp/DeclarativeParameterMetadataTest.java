/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpParam;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.annotations.Meta;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DeclarativeParameterMetadataTest {
    record Payload(String city) {}

    record Tenant(String tenant) {}

    static class Service {
        @McpTool
        String whole(@Meta Tenant meta, Payload payload) {
            return payload.city() + ":" + meta.tenant();
        }

        @McpTool
        String named(@McpParam(name = "payload") Payload arg0, @Meta @Nullable Tenant meta) {
            return arg0.city() + ":" + (meta == null ? "none" : meta.tenant());
        }

        @McpTool
        String raw(@Meta Map<String, Object> meta) {
            return String.valueOf(meta.getOrDefault("tenant", "none"));
        }

        @McpPrompt
        String prompt(@McpParam(name = "city", description = "Destination") String value, @Meta Tenant meta) {
            return value + ":" + meta.tenant();
        }

        @McpResource(uri = "city://{city}", mimeType = "text/plain")
        String resource(@Meta Tenant meta, @McpParam(name = "city") String value) {
            return value + ":" + meta.tenant();
        }

        @McpResource(uri = "city://static", mimeType = "text/plain")
        String staticResource(@Meta Map<String, Object> meta) {
            return String.valueOf(meta.get("tenant"));
        }

        @McpCompletion(prompt = "prompt")
        List<String> complete(@Meta Tenant meta, @McpParam(name = "city") String value) {
            return List.of(value + ":" + meta.tenant());
        }

        @McpCompletion(resource = "city://{city}")
        List<String> completeRequest(CompletionRequest request, @Meta Tenant meta) {
            return List.of(request.argumentValue() + ":" + meta.tenant());
        }
    }

    @Test
    void bindsWholeAndNamedObjectsWithSeparateMetadata() throws Exception {
        try (var server = McpTestServers.start(b -> b.annotations(a -> a.register(new Service())), s -> {});
                var client = McpTestClients.latest(server.port())) {
            for (var params : List.of("""
                    {"name":"whole","arguments":{"city":"Riga"},"_meta":{"tenant":"acme"}}
                    """, """
                    {"name":"named","arguments":{"payload":{"city":"Riga"}},"_meta":{"tenant":"acme"}}
                    """)) {
                assertThat(client.post("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":%s}
                        """.formatted(params))).isSuccess().hasResult("""
                        {"content":[{"type":"text","text":"Riga:acme"}],"resultType":"complete"}
                        """);
            }
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"named","arguments":{"payload":{"city":"Riga"}}}}
                    """)).isSuccess().hasResult("""
                    {"content":[{"type":"text","text":"Riga:null"}],"resultType":"complete"}
                    """);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                      "name":"whole","arguments":{"city":"Riga"},"_meta":{"tenant":{"bad":true}}}}
                    """)).isJsonRpcError().hasErrorCode(-32602);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"raw"}}
                    """)).isSuccess().hasResult("""
                    {"content":[{"type":"text","text":"none"}],"resultType":"complete"}
                    """);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
                      "name":"raw","_meta":{"tenant":"acme"}}}
                    """)).isSuccess().hasResult("""
                    {"content":[{"type":"text","text":"acme"}],"resultType":"complete"}
                    """);
        }
    }

    @Test
    void absentMetadataIsNullForNullableRecordsAndRejectedForRequiredRecords() throws Exception {
        try (var server = McpTestServers.start(b -> b.annotations(a -> a.register(new Service())), s -> {});
                var client = McpTestClients.forVersion(server.port(), "2025-11-25")) {
            client.initialize();
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                      "name":"named","arguments":{"payload":{"city":"Riga"}}}}
                    """)).isSuccess().hasResult("""
                    {"content":[{"type":"text","text":"Riga:none"}]}
                    """);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"whole","arguments":{"city":"Riga"}}}
                    """)).isJsonRpcError().hasErrorCode(-32602);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"raw"}}
                    """)).isSuccess().hasResult("""
                    {"content":[{"type":"text","text":"none"}]}
                    """);
        }
    }

    @Test
    void injectsMetadataAcrossPromptsResourcesAndCompletionShapes() throws Exception {
        try (var server = McpTestServers.start(b -> b.annotations(a -> a.register(new Service())), s -> {});
                var client = McpTestClients.latest(server.port())) {
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{
                      "name":"prompt","arguments":{"city":"Riga"},"_meta":{"tenant":"acme"}}}
                    """)).isSuccess().hasResult("""
                    {"messages":[{"role":"user","content":{"type":"text","text":"Riga:acme"}}],
                     "resultType":"complete"}
                    """);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{
                      "uri":"city://Riga","_meta":{"tenant":"acme"}}}
                    """)).isSuccess().hasResult("""
                    {"contents":[{"uri":"city://Riga","mimeType":"text/plain","text":"Riga:acme"}],
                     "cacheScope":"public","ttlMs":0,"resultType":"complete"}
                    """);
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"resources/read","params":{
                      "uri":"city://static","_meta":{"tenant":"acme"}}}
                    """)).isSuccess().hasResult("""
                    {"contents":[{"uri":"city://static","mimeType":"text/plain","text":"acme"}],
                     "cacheScope":"public","ttlMs":0,"resultType":"complete"}
                    """);
            for (var ref : List.of("""
                    {"type":"ref/prompt","name":"prompt"}
                    """, """
                    {"type":"ref/resource","uri":"city://{city}"}
                    """)) {
                assertThat(client.post("""
                        {"jsonrpc":"2.0","id":4,"method":"completion/complete","params":{
                          "ref":%s,"argument":{"name":"city","value":"Ri"},"_meta":{"tenant":"acme"}}}
                        """.formatted(ref))).isSuccess().hasResult("""
                        {"completion":{"values":["Ri:acme"]},"resultType":"complete"}
                        """);
            }
        }
    }
}
