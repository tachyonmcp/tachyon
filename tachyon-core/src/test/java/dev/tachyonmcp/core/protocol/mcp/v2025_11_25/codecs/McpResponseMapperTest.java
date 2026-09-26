/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import static dev.tachyonmcp.core.test.TestUtils.properties;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Annotations;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CompleteResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.GetPromptResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListPromptsResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListResourceTemplatesResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListResourcesResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListToolsResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Resource;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class McpResponseMapperTest {

    private static final String RELATED_TASK = "io.modelcontextprotocol/related-task";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final McpResponseMapper mapper = new McpResponseMapper();

    @Test
    void completedTaskPayloadIsCallToolResultWithContentAndRelatedTask() {
        var result = TaskResult.completed(ToolResult.Success.of(null, List.of(TextContent.of("done"))));

        var payload = (CallToolResult) mapper.getTaskPayloadResult(result, "task-1");

        assertThat(payload.isError()).isNull();
        assertThat(payload.content()).hasSize(1);
        assertThat(relatedTaskId(payload)).isEqualTo("task-1");
    }

    @Test
    void completedTaskPayloadCarriesStructuredContent() {
        var structured = JSON.objectNode().put("temp", 72);
        var result = TaskResult.completed(ToolResult.Success.of(structured, List.of()));

        var payload = (CallToolResult) mapper.getTaskPayloadResult(result, "task-2");

        assertThat(properties(payload.structuredContent())).containsKey("temp");
        // A structured-only result injects the serialized JSON as a text block (MCP backwards-compat).
        assertThat(payload.content()).isNotEmpty();
    }

    @Test
    void failedTaskPayloadSetsIsErrorTrue() {
        var result = TaskResult.completedWithError("boom");

        var payload = (CallToolResult) mapper.getTaskPayloadResult(result, "task-3");

        assertThat(payload.isError()).isTrue();
        assertThat(payload.content()).hasSize(1);
        assertThat(relatedTaskId(payload)).isEqualTo("task-3");
    }

    @Test
    void protocolFailurePayloadPreservesServerError() {
        var error = new ServerError(ServerError.Kind.INVALID_PARAMS, "Invalid params");
        var result = TaskResult.failed(error);

        assertThat(mapper.getTaskPayloadResult(result, "task-3")).isSameAs(error);
    }

    @Test
    void nullResultYieldsEmptyContentWithRelatedTask() {
        var payload = (CallToolResult) mapper.getTaskPayloadResult(null, "task-4");

        assertThat(payload.content()).isEmpty();
        assertThat(payload.isError()).isNull();
        assertThat(relatedTaskId(payload)).isEqualTo("task-4");
    }

    @Test
    void userMetaIsPreservedAlongsideRelatedTask() {
        var userMeta = Map.<String, Object>of("trace", "abc");
        var result = TaskResult.completed(ToolResult.Success.builder()
                .content(TextContent.of("ok"))
                .meta(userMeta)
                .build());

        var payload = (CallToolResult) mapper.getTaskPayloadResult(result, "task-5");

        assertThat(properties(payload._meta())).containsEntry("trace", JSON.stringNode("abc"));
        assertThat(relatedTaskId(payload)).isEqualTo("task-5");
    }

    @Test
    void resourceNotFoundKeepsTheLegacyCode() {
        var error = mapper.error(new ServerError(ServerError.Kind.RESOURCE_NOT_FOUND, "Resource not found"));

        assertThat(error.code()).isEqualTo(-32002);
    }

    @Test
    void domainMetadataMapsToLegacyResultAndDescriptorTypes() {
        var completion = (CompleteResult) mapper.completeResult(CompletionResult.builder()
                .values("one")
                .meta(Map.of("kind", "completion"))
                .build());
        var promptResult =
                (GetPromptResult) mapper.getPromptResult("Prompt", List.of(), Map.of("kind", "prompt-result"));
        var tools = (ListToolsResult) mapper.listToolsResult(
                List.of(ToolDescriptor.builder()
                        .name("tool")
                        .meta(Map.of("kind", "tool"))
                        .build()),
                null);
        var resources = (ListResourcesResult) mapper.listResourcesResult(
                List.of(ResourceDescriptor.builder()
                        .name("resource")
                        .uri("memory://resource")
                        .meta(Map.of("kind", "resource"))
                        .build()),
                null);
        var templates = (ListResourceTemplatesResult) mapper.listResourceTemplatesResult(
                List.of(ResourceTemplateDescriptor.builder()
                        .name("template")
                        .uriTemplate("memory://{id}")
                        .meta(Map.of("kind", "template"))
                        .build()),
                null);
        var prompts = (ListPromptsResult) mapper.listPromptsResult(
                List.of(PromptDescriptor.builder()
                        .name("prompt")
                        .meta(Map.of("kind", "prompt"))
                        .build()),
                null);

        assertThat(properties(completion._meta())).containsEntry("kind", JSON.stringNode("completion"));
        assertThat(properties(promptResult._meta())).containsEntry("kind", JSON.stringNode("prompt-result"));
        assertThat(properties(tools.tools().getFirst()._meta())).containsEntry("kind", JSON.stringNode("tool"));
        assertThat(properties(resources.resources().getFirst()._meta()))
                .containsEntry("kind", JSON.stringNode("resource"));
        assertThat(properties(templates.resourceTemplates().getFirst()._meta()))
                .containsEntry("kind", JSON.stringNode("template"));
        assertThat(properties(prompts.prompts().getFirst()._meta())).containsEntry("kind", JSON.stringNode("prompt"));
    }

    @Test
    void encodeSerializesProtocolModelsAsJsonNotToString() {
        var result = CallToolResult.ofText("ok");

        var json = mapper.encode(result);

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {"content":[{"type":"text","text":"ok"}]}
            """);
    }

    @Test
    void inputRequiredEncodesRequestedSchemaAsRawJsonNotWrappedInJsonSchemaObject() {
        var schema = JsonSchema.unchecked("""
                {"type":"object","properties":{"name":{"type":"string"}}}
                """);
        var request = FormInputRequest.of("What is your name?", schema);

        var payload = mapper.inputRequiredResult(Map.of("user_name", request), null, null);
        var json = mapper.encode(payload);

        assertThatJson(json)
                .inPath("$.inputRequests.user_name.params.requestedSchema")
                .isEqualTo("""
                        {"type":"object","properties":{"name":{"type":"string"}}}
                        """);
    }

    @Test
    void encodeSerializesEnumLists() {
        var annotations = new Annotations(List.of(Role.USER, Role.ASSISTANT), 0.8, "2026-07-23T00:00:00Z");
        var resource = Resource.builder()
                .uri("weather://prediction/article")
                .name("prediction-article")
                .annotations(annotations)
                .build();

        var json = mapper.encode(
                ListResourcesResult.builder().resources(List.of(resource)).build());

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {
              "resources": [{
                "uri": "weather://prediction/article",
                "name": "prediction-article",
                "annotations": {
                  "audience": ["user", "assistant"],
                  "priority": 0.8,
                  "lastModified": "2026-07-23T00:00:00Z"
                }
              }]
            }
            """);

        var decoded = (Map<String, ?>) JsonRpcCodec.readValue(json);
        var resources = (List<Map<String, ?>>) decoded.get("resources");
        var ann = (Map<String, ?>) resources.getFirst().get("annotations");
        var audience = ((List<?>) ann.get("audience"))
                .stream().map(s -> Role.fromValue((String) s)).toList();
        assertThat(audience).containsExactly(Role.USER, Role.ASSISTANT);
    }

    @Test
    void loggingMessageParamsSerializesLevelLoggerAndData() {
        var json = mapper.encode(mapper.loggingMessageParams(LoggingLevel.WARNING, "logger.x", "boom"));

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {"level":"warning","logger":"logger.x","data":"boom"}
            """);
    }

    @Test
    void loggingMessageParamsOmitsAbsentLoggerAndRetainsNullData() {
        var json = mapper.encode(mapper.loggingMessageParams(LoggingLevel.NOTICE, null, null));

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {"level":"notice","data":null}
            """);
        assertThat(json).doesNotContain("logger");
    }

    @Test
    void progressNotificationParamsPreservesNumericTokenType() {
        var json = mapper.encode(mapper.progressNotificationParams(ProgressToken.of(42), 1.0, 2.0, "working"));

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {"progressToken":42,"progress":1.0,"total":2.0,"message":"working"}
            """);
    }

    @Test
    void progressNotificationParamsPreservesStringToken() {
        var json = mapper.encode(mapper.progressNotificationParams(ProgressToken.of("token-1"), 0.5, 1.0, "halfway"));

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {"progressToken":"token-1","progress":0.5,"total":1.0,"message":"halfway"}
            """);
    }

    private static String relatedTaskId(CallToolResult payload) {
        JsonNode meta = payload._meta().get(RELATED_TASK);
        assertThat(meta).isNotNull();
        return meta.get("taskId").asString();
    }
}
