/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.ElicitationRequest;
import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class DefaultConformanceServer extends AbstractConformanceServer {

    @Override
    protected ServerEngine createServer(boolean isStateful) {
        final var builder = TachyonServer.builder()
                .capabilities(CapabilitiesConfig.Builder::logging)
                .network(n -> n.host("localhost"));
        if (isStateful) {
            builder.session(SessionConfig.Builder::enabled);
        }
        return (ServerEngine) builder.build();
    }

    /**
     * Tools scoped to the latest stable conformance suite (protocol version 2025-11-25) that the
     * draft suite no longer exercises: logging, sampling, elicitation, and the SEP-1034/SEP-1330
     * elicitation defaults/enums scenarios.
     */
    @Override
    protected void registerVersionSpecificTools(ServerEngine server) {
        server.tools()
                .register(
                        tool -> tool.name("test_tool_with_logging")
                                .description("Tool with logging")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> {
                            ctx.notifications().info("tachyon.tools", Map.of("message", "Tool execution started"));
                            delay(50);
                            ctx.notifications().info("tachyon.tools", Map.of("message", "Tool processing data"));
                            delay(50);
                            ctx.notifications().info("tachyon.tools", Map.of("message", "Tool execution completed"));
                            return ToolResult.text("Tool execution completed");
                        });

        server.tools()
                .register(
                        b -> b.name("test_sampling")
                                .description("Tool that requests sampling")
                                .inputSchema(INPUT_SCHEMA_WITH_PROMPT),
                        (ctx, request) -> {
                            var promptOpt = request.arguments().stringOpt("prompt");
                            if (promptOpt.isPresent()) {
                                var prompt = promptOpt.get();
                                try {
                                    var params = Args.of(Map.of(
                                            "messages",
                                            List.of(Map.of(
                                                    "role", "user", "content", Map.of("type", "text", "text", prompt))),
                                            "maxTokens",
                                            100));
                                    var result = ctx.client()
                                            .sampling()
                                            .createMessage(params)
                                            .get(2, TimeUnit.SECONDS);
                                    var text = result.objectOpt("content")
                                            .flatMap(c -> c.stringOpt("text"))
                                            .orElse("");
                                    return ToolResult.text(text);
                                } catch (Exception e) {
                                    return ToolResult.error("Sampling request failed");
                                }
                            }
                            return ToolResult.text("sampling not fully implemented");
                        });

        server.tools()
                .register(
                        b -> b.name("test_elicitation")
                                .description("Tool that requests elicitation")
                                .inputSchema(INPUT_SCHEMA_WITH_MESSAGE),
                        (ctx, request) -> {
                            var messageOpt = request.arguments().stringOpt("message");
                            if (messageOpt.isPresent()) {
                                var message = messageOpt.get();
                                try {
                                    var schema = JsonSchema.unchecked(JsonRpcCodec.writeValueAsString(Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "username", Map.of("type", "string"),
                                                    "email", Map.of("type", "string")),
                                            "required",
                                            List.of("username", "email"))));
                                    var result = ctx.client()
                                            .elicitation()
                                            .create(ElicitationRequest.of(message, schema))
                                            .get(2, TimeUnit.SECONDS);
                                    var text = "User response: "
                                            + result.action().name().toLowerCase(Locale.ROOT);
                                    if (result.content() != null)
                                        text += ", " + result.content().asMap();
                                    return ToolResult.text(text);
                                } catch (Exception e) {
                                    return ToolResult.error("Elicitation request failed");
                                }
                            }
                            return ToolResult.text("elicitation not fully implemented");
                        });

        server.tools()
                .register(
                        b -> b.name("test_elicitation_sep1034_defaults")
                                .description("Elicitation with defaults")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> {
                            try {
                                var schema = JsonSchema.unchecked(JsonRpcCodec.writeValueAsString(Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.<String, Object>of(
                                                "name",
                                                Map.of("type", "string", "default", "John Doe"),
                                                "age",
                                                Map.of("type", "integer", "default", 30),
                                                "score",
                                                Map.of("type", "number", "default", 95.5),
                                                "status",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "enum",
                                                        List.of("active", "inactive"),
                                                        "default",
                                                        "active"),
                                                "verified",
                                                Map.of("type", "boolean", "default", true)))));
                                var result = ctx.client()
                                        .elicitation()
                                        .create(ElicitationRequest.of(
                                                "Please provide your details with defaults", schema))
                                        .get(2, TimeUnit.SECONDS);
                                var text = "Defaults " + result.action().name().toLowerCase(Locale.ROOT);
                                if (result.content() != null)
                                    text += ", " + result.content().asMap();
                                return ToolResult.text(text);
                            } catch (Exception e) {
                                return ToolResult.error("Error: " + e.getMessage());
                            }
                        });

        server.tools()
                .register(
                        b -> b.name("test_elicitation_sep1330_enums")
                                .description("Elicitation with enums")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> {
                            try {
                                var props = new LinkedHashMap<String, Object>();
                                props.put(
                                        "untitledSingle",
                                        Map.of("type", "string", "enum", List.of("option1", "option2", "option3")));
                                props.put(
                                        "titledSingle",
                                        Map.of(
                                                "type",
                                                "string",
                                                "oneOf",
                                                List.of(
                                                        Map.of("const", "opt_a", "title", "Option A"),
                                                        Map.of("const", "opt_b", "title", "Option B"))));
                                props.put(
                                        "legacyEnum",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of("val1", "val2"),
                                                "enumNames",
                                                List.of("Value 1", "Value 2")));
                                props.put(
                                        "untitledMulti",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of("type", "string", "enum", List.of("x", "y", "z"))));
                                props.put(
                                        "titledMulti",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "anyOf",
                                                        List.of(
                                                                Map.of("const", "item1", "title", "Item One"),
                                                                Map.of("const", "item2", "title", "Item Two")))));
                                var schema = JsonSchema.unchecked(
                                        JsonRpcCodec.writeValueAsString(Map.of("type", "object", "properties", props)));
                                var result = ctx.client()
                                        .elicitation()
                                        .create(ElicitationRequest.of("Please select your preferences", schema))
                                        .get(2, TimeUnit.SECONDS);
                                var text = "Enums " + result.action().name().toLowerCase(Locale.ROOT);
                                if (result.content() != null)
                                    text += ", " + result.content().asMap();
                                return ToolResult.text(text);
                            } catch (Exception e) {
                                return ToolResult.error("Error: " + e.getMessage());
                            }
                        });
    }
}
