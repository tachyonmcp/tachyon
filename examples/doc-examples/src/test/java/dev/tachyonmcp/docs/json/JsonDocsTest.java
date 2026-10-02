package dev.tachyonmcp.docs.json;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.post;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

class JsonDocsTest {

    @Test
    void schemaLiteralDrivesInputValidation() throws Exception {
        try (var server = McpTestServers.start(
                b -> {},
                s -> s.tools().register(
                        tool -> tool.name("forecast").inputSchema(JsonSchemas.literal()),
                        (ctx, request) -> ToolResult.text("ok")))) {
            assertThatResponse(post(server, "tools/call", """
                    {"name":"forecast","arguments":{"city":"Paris","days":3}}
                    """)).hasStatus(200).isSuccess();
            for (var invalid : new String[] {"{\"days\":3}", "{\"city\":\"Paris\",\"days\":0}"}) {
                assertThatResponse(post(server, "tools/call", "{\"name\":\"forecast\",\"arguments\":%s}".formatted(invalid)))
                        .hasStatus(400)
                        .isJsonRpcError()
                        .hasErrorCode(-32602);
            }
        }
    }

    @Test
    void parseRejectsMalformedJsonWhileTheTrustedFactoryDoesNot() {
        assertThat(JsonSchemas.external("{\"type\":\"object\"}").json()).contains("object");
        assertThatThrownBy(() -> JsonSchemas.external("{not json")).isInstanceOf(IllegalArgumentException.class);

        var documents = JsonSchemas.documents("{\"a\":1}", "{\"b\":2}");
        assertThat(documents.trusted().json()).isEqualTo("{\"a\":1}");
        assertThat(documents.checked().json()).contains("\"b\"");
        assertThatThrownBy(() -> JsonSchemas.documents("{\"a\":1}", "{broken"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void objectAndArrayAccessorsReadTypedValues() {
        var user = JsonAccess.user();

        assertThat(user.name()).isEqualTo("Ada");
        assertThat(user.age()).isEqualTo(32);
        assertThat(user.address()).isEmpty();
        assertThat(user.roles()).containsExactly("admin", "author");
        assertThat(JsonAccess.coordinates()).containsExactly(59.437, 24.7536);
    }

    @Test
    void accessorsNeverCoerceAndRequiredValuesThrowWhenMissing() {
        var object = JsonObject.of(Map.of("name", "Ada", "fraction", 1.5, "big", Long.MAX_VALUE));

        assertThatThrownBy(() -> object.intValue("name")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> object.intValue("fraction")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> object.intValue("big")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> object.stringValue("missing")).isInstanceOf(IllegalArgumentException.class);
        assertThat(object.intOr("missing", 7)).isEqualTo(7);
        assertThat(object.stringOpt("missing")).isEmpty();
    }

    @Test
    void jacksonTreeIsWrappedWithoutCopyingAndRecoveredByProviderType() {
        var document = JsonAccess.wrap(new ObjectMapper(), "{\"a\":1}");

        var node = JsonAccess.unwrap(document);
        assertThat(node.path("a").asInt()).isEqualTo(1);

        var text = (StringNode) new ObjectMapper().readTree("\"x\"");
        assertThat(JsonDocument.from(text, JsonNode.class).json()).isEqualTo("\"x\"");
        assertThatThrownBy(() -> JsonDocument.from(text, StringNode.class))
                .as("pass the provider's type, not the source implementation class")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No JsonDocumentFactory");
    }

    @Test
    void customSerdeSerializesStructuredResults() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.json.CustomSerdeServer")) {
            var structured = result(DOCUMENTED_PORT, "tools/call", """
                    {"name":"city","arguments":{}}
                    """).path("structuredContent");

            assertThat(structured.path("serializedBy").asString()).isEqualTo("custom");
            assertThat(structured.path("value").path("city").asString()).isEqualTo("Paris");
        }
    }

    @Test
    void customValidatorsReplaceTheInputCheckAndDisableOutputChecks() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.json.CustomValidatorsServer")) {
            var accepted = result(DOCUMENTED_PORT, "tools/call", """
                    {"name":"weather","arguments":{"note":"sunny"}}
                    """);
            assertThat(accepted.path("isError").asBoolean()).as("noop output validator").isFalse();
            assertThat(accepted.path("structuredContent").path("temp").asString()).isEqualTo("hot");

            assertThatResponse(post(DOCUMENTED_PORT, "tools/call", """
                    {"name":"weather","arguments":{"note":"forbidden"}}
                    """)).hasStatus(400).isJsonRpcError().hasErrorCode(-32602);
        }
        try (var server = McpTestServers.start(
                b -> {},
                s -> s.tools().register(
                        tool -> tool.name("weather")
                                .inputSchema(JsonSchemasHolder.WEATHER_INPUT)
                                .outputSchema(JsonSchemasHolder.WEATHER_OUTPUT),
                        (ctx, request) -> ToolResult.structured(Map.of("temp", "hot"))))) {
            var rejected = result(server, "tools/call", """
                    {"name":"weather","arguments":{}}
                    """);
            assertThat(rejected.path("isError").asBoolean()).as("default output validation").isTrue();
        }
    }
}
