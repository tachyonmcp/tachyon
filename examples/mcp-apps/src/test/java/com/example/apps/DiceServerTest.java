/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.apps;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.apps.AppsExtension;
import dev.tachyonmcp.testkit.JsonRpcResponseAssert;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiceServerTest {

    private final TachyonServer server = DiceServer.buildServer("localhost", 0, null);
    private Mcp20260728Client client;

    @BeforeAll
    void startServer() {
        server.start();
        var settings = JsonNodeFactory.instance.objectNode();
        settings.putArray("mimeTypes").add(AppsExtension.MIME_TYPE);
        client = new Mcp20260728Client(server.port()).withExtensions(Map.of(AppsExtension.ID, settings));
    }

    @AfterAll
    void stopServer() {
        client.close();
        server.close();
    }

    @Test
    void shouldAdvertiseAppsExtension() throws Exception {
        // language=json
        client.discover()
            .isSuccess()
            .hasCapabilities("""
                {"tools":{},"resources":{},"extensions":{"io.modelcontextprotocol/ui":{}}}
                """);
    }

    @Test
    void shouldServeViewAsMcpAppResource() throws Exception {
        // language=json
        var response = client.sendRpc("""
            {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"ui://dice/roll"}}
            """);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var html = new ObjectMapper().writeValueAsString(DiceServer.classpath("apps/dice.html"));
        // language=json
        JsonRpcResponseAssert.assertThat(response)
            .isSuccess()
            .hasId(2)
            .hasResult("""
                {
                  "resultType": "complete",
                  "cacheScope": "public",
                  "ttlMs": 0,
                  "contents": [{
                    "uri": "ui://dice/roll",
                    "mimeType": "text/html;profile=mcp-app",
                    "text": %s,
                    "_meta": {"ui": {"prefersBorder": true}}
                  }]
                }
                """.formatted(html));
    }

    @Test
    void shouldLinkToolToView() throws Exception {
        // language=json
        var response = client.sendRpc("""
            {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}
            """);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        // language=json
        JsonRpcResponseAssert.assertThat(response).isSuccess().hasId(3).hasResult("""
            {
              "resultType": "complete",
              "cacheScope": "public",
              "ttlMs": 0,
              "tools": [{
                "name": "roll_dice",
                "description": "Roll one or more six-sided dice",
                "inputSchema": {"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":10}}},
                "_meta": {"ui": {"resourceUri": "ui://dice/roll"}}
              }]
            }
            """);
    }

    @Test
    void shouldReturnTextAndStructuredRolls() throws Exception {
        // language=json
        var response = client.sendRpc("""
            {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"roll_dice","arguments":{"count":3}}}
            """);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result = JsonRpcResponseAssert.assertThat(response).isSuccess().hasId(4).result();
        var rolls = new ArrayList<Integer>();
        result.path("structuredContent").path("rolls").forEach(roll -> rolls.add(roll.asInt()));
        assertThat(rolls).hasSize(3).allSatisfy(roll -> assertThat(roll).isBetween(1, 6));
        assertThat(result.path("content")).hasSize(1);
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("Rolled " + rolls);
    }
}
