/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Elicitation schemas reach the client unchanged inside an {@code InputRequiredResult}: defaults on
 * every primitive type (SEP-1034) and every enum shape (SEP-1330). Same schemas as the
 * 2025-11-25 conformance fixtures {@code test_elicitation_sep1034_defaults} and
 * {@code test_elicitation_sep1330_enums}.
 */
class ElicitationSchemaTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    // language=JSON
    private static final String DEFAULTS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {"type": "string", "default": "John Doe"},
                "age": {"type": "integer", "default": 30},
                "score": {"type": "number", "default": 95.5},
                "status": {"type": "string", "enum": ["active", "inactive"], "default": "active"},
                "verified": {"type": "boolean", "default": true}
              }
            }
            """;

    // language=JSON
    private static final String ENUMS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "untitledSingle": {"type": "string", "enum": ["option1", "option2", "option3"]},
                "titledSingle": {
                  "type": "string",
                  "oneOf": [{"const": "opt_a", "title": "Option A"}, {"const": "opt_b", "title": "Option B"}]
                },
                "legacyEnum": {"type": "string", "enum": ["val1", "val2"], "enumNames": ["Value 1", "Value 2"]},
                "untitledMulti": {"type": "array", "items": {"type": "string", "enum": ["x", "y", "z"]}},
                "titledMulti": {
                  "type": "array",
                  "items": {
                    "type": "string",
                    "anyOf": [{"const": "item1", "title": "Item One"}, {"const": "item2", "title": "Item Two"}]
                  }
                }
              }
            }
            """;

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Test
    @DisplayName("[SEP-1034] elicitation schema keeps a default on every primitive type")
    void elicitationSchemaKeepsDefaults() throws Exception {
        assertSchemaRoundTrips("Please provide your details with defaults", DEFAULTS_SCHEMA);
    }

    @Test
    @DisplayName("[SEP-1330] elicitation schema keeps titled, untitled, legacy, single- and multi-select enums")
    void elicitationSchemaKeepsEnums() throws Exception {
        assertSchemaRoundTrips("Please select your preferences", ENUMS_SCHEMA);
    }

    private void assertSchemaRoundTrips(String message, String schema) throws Exception {
        startServerWith(s -> s.tools()
                .register(
                        tool -> tool.name("ask").inputSchema(JsonSchema.objectSchema()),
                        (ctx, request) -> ToolResult.inputRequired(
                                Map.of("details", FormInputRequest.of(message, JsonSchema.unchecked(schema))), null)));

        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ask","arguments":{}}}
                    """);
            // language=JSON
            assertThat(response).isSuccess().hasId(1).hasResult("""
                    {
                      "resultType": "input_required",
                      "inputRequests": {
                        "details": {
                          "method": "elicitation/create",
                          "params": {"message": "%s", "requestedSchema": %s}
                        }
                      }
                    }
                    """.formatted(message, schema));
        }
    }
}
