/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs;

import static dev.tachyonmcp.core.test.TestUtils.properties;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.ImageContent;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper.SubscriptionListenRequest;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CompleteResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.GetPromptResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListPromptsResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListResourceTemplatesResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListResourcesResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListToolsResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.NotificationParams;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class McpResponseMapperTest {

    private final McpResponseMapper mapper = new McpResponseMapper();

    @Test
    void resourceNotFoundUsesInvalidParams() {
        var error = mapper.error(new ServerError(ServerError.Kind.RESOURCE_NOT_FOUND, "Resource not found"));

        assertThat(error.code()).isEqualTo(-32602);
    }

    @Test
    void unsupportedProtocolVersionUsesTheModernCode() {
        var error = mapper.error(
                new ServerError(ServerError.Kind.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version"));

        assertThat(error.code()).isEqualTo(-32022);
    }

    @Test
    void completeResultsUseTheModernDiscriminator() {
        var domain = CompletionResult.builder()
                .values("one")
                .total(100500)
                .hasMore(false)
                .meta(Map.of("trace", "complete-1"))
                .build();
        var result = (CompleteResult) mapper.completeResult(domain);

        assertThat(result.resultType()).isEqualTo("complete");
        assertThat(result.completion().values()).containsExactly("one");
        assertThat(result.completion().total()).isEqualTo(100500);
        assertThat(properties(result._meta()))
                .containsEntry("trace", JsonNodeFactory.instance.stringNode("complete-1"));
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
    void toolResultsPreserveArbitraryStructuredValuesAndMetadata() {
        var result = (CallToolResult) mapper.callToolResult(
                ToolResult.structured(JsonDocument.of("[1,true]")).withMeta("trace", Map.of("id", 7)));

        assertThat(result.resultType()).isEqualTo("complete");
        assertThat(result.structuredContent())
                .isEqualTo(JsonNodeFactory.instance.arrayNode().add(1).add(true));
        assertThat(properties(result._meta()))
                .containsEntry("trace", JsonNodeFactory.instance.objectNode().put("id", 7));
        assertThat(result.content()).hasSize(1);
    }

    @Test
    void completedTaskInlinesBinaryContentAndNumbersAsOnTheWire() {
        var result = ToolResult.Success.of(
                Map.of("count", 3, "ratio", 0.5),
                List.of(ImageContent.of(new byte[] {1, 2, 3}, "image/png"), TextContent.of("done")));
        var snapshot = TaskSnapshot.completed("task-1", Instant.EPOCH, Instant.EPOCH, 2, result);

        var json = mapper.encode(mapper.getTaskResult(snapshot));

        assertThatJson(json).inPath("$.result").isEqualTo("""
                {
                  "content": [
                    {"type": "image", "data": "AQID", "mimeType": "image/png"},
                    {"type": "text", "text": "done"}
                  ],
                  "structuredContent": {"count": 3, "ratio": 0.5},
                  "resultType": "complete"
                }""");
    }

    @Test
    void promptResultsUseModernContentAndDiscriminator() {
        var result = (GetPromptResult) mapper.getPromptResult(
                "Greeting", List.of(PromptMessage.of(Role.USER, TextContent.of("Hello"))), Map.of("trace", "prompt-1"));

        assertThat(result.resultType()).isEqualTo("complete");
        assertThat(result.description()).isEqualTo("Greeting");
        assertThat(result.messages()).hasSize(1);
        assertThat(properties(result._meta())).containsEntry("trace", JsonNodeFactory.instance.stringNode("prompt-1"));
    }

    @Test
    void listResultsPreserveDescriptorAnnotationsIconsAndArguments() {
        var icon = Icon.of("https://example.test/icon.png", "image/png", List.of("16x16"), "light");
        var annotations = Annotations.of(List.of(Role.USER), 0.5, "2026-07-29T00:00:00Z");
        var toolAnnotations = ToolAnnotations.of("Safe", true, false, true, false);
        var tool = ToolDescriptor.builder()
                .name("weather")
                .annotations(toolAnnotations)
                .icons(icon)
                .meta(Map.of("kind", "tool"))
                .build();
        var resource = ResourceDescriptor.builder()
                .name("forecast")
                .uri("memory://forecast")
                .annotations(annotations)
                .icons(icon)
                .meta(Map.of("kind", "resource"))
                .build();
        var template = ResourceTemplateDescriptor.builder()
                .name("city")
                .uriTemplate("memory://forecast/{city}")
                .annotations(annotations)
                .icons(icon)
                .meta(Map.of("kind", "template"))
                .build();
        var prompt = PromptDescriptor.builder()
                .name("greet")
                .addArguments(PromptArgument.of("name", "Name", "Who to greet", true))
                .icons(List.of(icon))
                .meta(Map.of("kind", "prompt"))
                .build();

        var toolResult = (ListToolsResult) mapper.listToolsResult(List.of(tool), null);
        var resourceResult = (ListResourcesResult) mapper.listResourcesResult(List.of(resource), null);
        var templateResult = (ListResourceTemplatesResult) mapper.listResourceTemplatesResult(List.of(template), null);
        var promptResult = (ListPromptsResult) mapper.listPromptsResult(List.of(prompt), null);

        assertThat(toolResult.tools().getFirst().annotations().readOnlyHint()).isTrue();
        assertThat(toolResult.tools().getFirst().icons()).hasSize(1);
        assertThat(properties(toolResult.tools().getFirst()._meta()))
                .containsEntry("kind", JsonNodeFactory.instance.stringNode("tool"));
        assertThat(resourceResult.resources().getFirst().annotations().priority())
                .isEqualTo(0.5);
        assertThat(resourceResult.resources().getFirst().icons()).hasSize(1);
        assertThat(properties(resourceResult.resources().getFirst()._meta()))
                .containsEntry("kind", JsonNodeFactory.instance.stringNode("resource"));
        assertThat(templateResult.resourceTemplates().getFirst().annotations().priority())
                .isEqualTo(0.5);
        assertThat(templateResult.resourceTemplates().getFirst().icons()).hasSize(1);
        assertThat(properties(templateResult.resourceTemplates().getFirst()._meta()))
                .containsEntry("kind", JsonNodeFactory.instance.stringNode("template"));
        assertThat(promptResult.prompts().getFirst().arguments().getFirst().name())
                .isEqualTo("name");
        assertThat(promptResult.prompts().getFirst().icons()).hasSize(1);
        assertThat(properties(promptResult.prompts().getFirst()._meta()))
                .containsEntry("kind", JsonNodeFactory.instance.stringNode("prompt"));
    }

    @Test
    void emptyDescriptorFieldsOmitIconsAudienceArgumentsAndSizesOnWire() {
        var iconWithNoSizes = Icon.of("https://example.test/icon.png", "image/png", List.of(), null);
        var annotations = Annotations.of(List.of(), 0.5, null);
        var tool = ToolDescriptor.builder().name("bare-tool").build();
        var resource = ResourceDescriptor.builder()
                .name("bare-resource")
                .uri("memory://bare")
                .annotations(annotations)
                .icons(iconWithNoSizes)
                .build();
        var prompt = PromptDescriptor.builder().name("bare-prompt").build();

        var toolResult = (ListToolsResult) mapper.listToolsResult(List.of(tool), null);
        var resourceResult = (ListResourcesResult) mapper.listResourcesResult(List.of(resource), null);
        var promptResult = (ListPromptsResult) mapper.listPromptsResult(List.of(prompt), null);

        var toolJson = new String(
                CodecRegistry.codecFor(ListToolsResult.class).encodeToBytes(toolResult), StandardCharsets.UTF_8);
        var resourceJson = new String(
                CodecRegistry.codecFor(ListResourcesResult.class).encodeToBytes(resourceResult),
                StandardCharsets.UTF_8);
        var promptJson = new String(
                CodecRegistry.codecFor(ListPromptsResult.class).encodeToBytes(promptResult), StandardCharsets.UTF_8);

        assertThat(toolJson).doesNotContain("\"icons\"");
        assertThat(resourceJson)
                .contains("\"icons\"")
                .doesNotContain("\"audience\"")
                .doesNotContain("\"sizes\"");
        assertThat(promptJson).doesNotContain("\"arguments\"");
    }

    @Test
    void subscriptionPayloadsSerializeThroughTheProtocolVersionsCodecs() {
        var id = RequestId.of("sub-1");
        var filter = new SubscriptionListenRequest(true, false, false, Set.of("memory://one"), Set.of("task-1"));

        var ack = mapper.encode(mapper.subscriptionsAcknowledgedParams(id, filter));
        var listChanged = mapper.encode(mapper.subscriptionListChangedParams(id));
        var updated = mapper.encode(mapper.subscriptionResourceUpdatedParams(id, "memory://one"));
        var graceful = mapper.encode(mapper.subscriptionsListenGracefulResult(id));

        // language=JSON
        assertThatJson(ack).isEqualTo("""
            {
              "notifications": {
                "toolsListChanged": true,
                "resourceSubscriptions": ["memory://one"],
                "taskIds": ["task-1"]
              },
              "_meta": {"io.modelcontextprotocol/subscriptionId": "sub-1"}
            }
            """);
        assertThat(ack).doesNotContain("promptsListChanged").doesNotContain("resourcesListChanged");
        // language=JSON
        assertThatJson(listChanged).isEqualTo("""
            {"_meta": {"io.modelcontextprotocol/subscriptionId": "sub-1"}}
            """);
        // language=JSON
        assertThatJson(updated).isEqualTo("""
            {"uri": "memory://one", "_meta": {"io.modelcontextprotocol/subscriptionId": "sub-1"}}
            """);
        // language=JSON
        assertThatJson(graceful).isEqualTo("""
            {"_meta": {"io.modelcontextprotocol/subscriptionId": "sub-1"}, "resultType": "complete"}
            """);
    }

    @Test
    void taskStatusNotificationContainsTheCompleteDetailedTask() {
        var observedAt = Instant.parse("2026-08-28T10:00:00Z");
        var completed = TaskSnapshot.builder()
                .taskId("task-1")
                .status(TaskState.COMPLETED)
                .createdAt(observedAt)
                .lastUpdatedAt(observedAt.plusSeconds(1))
                .result(TaskResult.completed(Map.of("answer", 42)))
                .revision(2)
                .build();

        var json = mapper.encode(mapper.taskStatusNotificationParams(completed));

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {
              "taskId":"task-1",
              "status":"completed",
              "createdAt":"2026-08-28T10:00:00Z",
              "lastUpdatedAt":"2026-08-28T10:00:01Z",
              "ttlMs":null,
              "result":{
                "content":[{"type":"text","text":"{\\\"answer\\\":42}"}],
                "structuredContent":{"answer":42},
                "resultType":"complete"
              }
            }
            """);
    }

    @Test
    void subscriptionIdPreservesNumericTypeFromTheRequestId() {
        var id = RequestId.of(1);
        var filter = new SubscriptionListenRequest(true, false, false, Set.of(), Set.of());

        var ack = mapper.encode(mapper.subscriptionsAcknowledgedParams(id, filter));

        // language=JSON
        assertThatJson(ack).isEqualTo("""
            {
              "notifications": {"toolsListChanged": true},
              "_meta": {"io.modelcontextprotocol/subscriptionId": 1}
            }
            """);
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
    void progressNotificationParamsPreservesNumericTokenType() {
        var json = mapper.encode(mapper.progressNotificationParams(ProgressToken.of(42), 1.0, 2.0, "working"));

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {"progressToken":42,"progress":1.0,"total":2.0,"message":"working"}
            """);
    }

    @Test
    void modelsReferencedByGeneratedCodecsAreRegistered() {
        assertThat(CodecRegistry.codecFor(NotificationParams.class)).isNotNull();
        assertThat(CodecRegistry.codecFor(ListToolsResult.class)).isNotNull();
    }
}
