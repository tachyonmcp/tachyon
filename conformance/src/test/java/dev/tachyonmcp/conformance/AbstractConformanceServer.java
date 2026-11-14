/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.AudioContent;
import dev.tachyonmcp.api.server.domain.BlobResourceContents;
import dev.tachyonmcp.api.server.domain.EmbeddedResource;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.ImageContent;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.RpcMethodRequest;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.tools.AbstractToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;

public abstract class AbstractConformanceServer {

    protected static final JsonSchema ANY_ARGS_SCHEMA = JsonSchema.objectSchema();

    // language=json
    protected static final JsonSchema INPUT_SCHEMA_NO_ARGS = parseJson("""
        {"type": "object", "properties": {}, "additionalProperties": false}
        """);

    // language=json
    protected static final JsonSchema INPUT_SCHEMA_WITH_PROMPT = parseJson("""
        {
            "type": "object",
            "properties": {"prompt": {"type": "string"}},
            "required": ["prompt"],
            "additionalProperties": false
        }
        """);

    // language=json
    protected static final JsonSchema INPUT_SCHEMA_WITH_MESSAGE = parseJson("""
        {
            "type": "object",
            "properties": {"message": {"type": "string"}},
            "required": ["message"],
            "additionalProperties": false
        }
        """);

    private static final String MINI_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";
    private static final byte[] MINI_PNG_BYTES = Base64.getDecoder().decode(MINI_PNG_BASE64);

    private static final String MINI_WAV_BASE64 = "UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=";
    private static final byte[] MINI_WAV_BYTES = Base64.getDecoder().decode(MINI_WAV_BASE64);

    private static final String HMAC_SECRET = "conformance-test-hmac-secret";

    protected static JsonSchema parseJson(String json) {
        return JsonSchema.from(json, String.class);
    }

    protected static FormInputRequest buildFormElicitation(String message, String propName, String propType) {
        var schema = JsonSchema.from(Map.of(
                "type", "object",
                "properties", Map.of(propName, Map.of("type", propType)),
                "required", List.of(propName)));
        return FormInputRequest.builder()
                .message(message)
                .requestedSchema(schema)
                .build();
    }

    protected static RpcMethodRequest buildSamplingRequest(String questionText) {
        var message = Map.of("role", "user", "content", Map.of("type", "text", "text", questionText));
        return RpcMethodRequest.of("sampling/createMessage", Map.of("messages", List.of(message), "maxTokens", 100));
    }

    protected static RpcMethodRequest buildRootsListRequest() {
        return RpcMethodRequest.of("roots/list", Map.of());
    }

    protected static String signState(String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(), "HmacSHA256"));
            var hmac = mac.doFinal(payload.getBytes());
            return payload + "." + Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected static boolean verifyState(@Nullable String signedState) {
        if (signedState == null) return false;
        var lastDot = signedState.lastIndexOf('.');
        if (lastDot < 0) return false;
        var payload = signedState.substring(0, lastDot);
        return signState(payload).equals(signedState);
    }

    private static JsonSchema buildJsonSchema() {
        // language=json
        return JsonSchema.parse("""
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "type": "object",
                "$defs": {
                    "address": {
                        "$anchor": "addressDef",
                        "type": "object",
                        "properties": {
                            "street": {"type": "string"},
                            "city": {"type": "string"}
                        }
                    }
                },
                "properties": {
                    "name": {"type": "string"},
                    "address": {"$ref": "#/$defs/address"},
                    "contactMethod": {"type": "string", "enum": ["phone", "email"]},
                    "phone": {"type": "string"},
                    "email": {"type": "string"}
                },
                "allOf": [{"anyOf": [{"required": ["phone"]}, {"required": ["email"]}]}],
                "if": {
                    "properties": {"contactMethod": {"const": "phone"}},
                    "required": ["contactMethod"]
                },
                "then": {"required": ["phone"]},
                "else": {"required": ["email"]},
                "additionalProperties": false
            }
            """);
    }

    protected static void delay(long millis) {
        Awaitility.await().pollDelay(millis, TimeUnit.MILLISECONDS).until(() -> true);
    }

    protected abstract ServerEngine createServer(boolean isStateful);

    /**
     * Creates and configures a conformance-test server.
     *
     * @param isStateful whether the server should maintain state between requests
     * @return the configured server
     */
    ServerEngine startServer(boolean isStateful) {
        var srv = createServer(isStateful);
        registerTools(srv);
        registerResources(srv);
        registerPrompts(srv);
        return srv;
    }

    /**
     * Registers the conformance-test tools, including tools for content types, progress,
     * logging, sampling, elicitation, JSON Schema validation, reconnection, and input-required results.
     */
    private void registerTools(ServerEngine server) {
        // language=json
        var echoSchema = parseJson("""
            {
                "type": "object",
                "properties": {"message": {"type": "string", "description": "Message to echo"}}
            }
            """);
        server.tools()
                .register(
                        b -> b.name("echo")
                                .description("Echo back the input message")
                                .inputSchema(echoSchema),
                        (ctx, request) -> ToolResult.text(request.arguments().stringOr("message", "")));

        server.tools()
                .register(
                        b -> b.name("test_simple_text")
                                .description("Returns simple text")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> ToolResult.text("This is a simple text response for testing."));

        server.tools()
                .register(
                        b -> b.name("test_image_content")
                                .description("Returns image content")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> ToolResult.content(ImageContent.of(MINI_PNG_BYTES, "image/png")));

        server.tools()
                .register(
                        b -> b.name("test_audio_content")
                                .description("Returns audio content")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> ToolResult.content(AudioContent.of(MINI_WAV_BYTES, "audio/wav")));

        server.tools()
                .register(
                        b -> b.name("test_embedded_resource")
                                .description("Returns embedded resource")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> {
                            var res = TextResourceContents.of(
                                    "test://embedded-resource", "This is an embedded resource content.", "text/plain");
                            return ToolResult.content(EmbeddedResource.of(res));
                        });

        server.tools()
                .register(
                        b -> b.name("test_multiple_content_types")
                                .description("Returns multiple content types")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> {
                            var mixed = TextResourceContents.of(
                                    "test://mixed-content-resource",
                                    "{\"test\":\"data\",\"value\":123}",
                                    "application/json");
                            return ToolResult.content(
                                    TextContent.of("Multiple content types test:"),
                                    ImageContent.of(MINI_PNG_BYTES, "image/png"),
                                    EmbeddedResource.of(mixed));
                        });

        server.tools()
                .register(
                        b -> b.name("test_error_handling")
                                .description("Always returns error")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> ToolResult.error("This tool intentionally returns an error for testing"));

        server.tools()
                .register(
                        tool -> tool.name("test_tool_with_progress")
                                .description("Tool with progress notifications")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> {
                            var pt = request.progressToken();
                            if (pt != null) {
                                ctx.notifications().progress(pt, 0, 100, "Starting");
                                delay(50);
                                ctx.notifications().progress(pt, 50, 100, "Halfway");
                                delay(50);
                                ctx.notifications().progress(pt, 100, 100, "Complete");
                            }
                            return ToolResult.text("Tool execution completed");
                        });

        var inputSchema = buildJsonSchema();
        server.tools()
                .register(
                        ToolDescriptor.builder()
                                .name("json_schema_2020_12_tool")
                                .description("Tool with JSON Schema 2020-12 features")
                                .inputSchema(inputSchema)
                                .build(),
                        (context, request) -> ToolResult.text("JSON Schema 2020-12 tool called"));

        server.tools()
                .register(
                        b -> b.name("test_reconnection")
                                .description(
                                        "A tool that triggers SSE stream closure to test client reconnection behaviour")
                                .inputSchema(INPUT_SCHEMA_NO_ARGS),
                        (ctx, request) -> {
                            var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
                            if (stream != null) {
                                stream.start();
                                stream.close();
                            }
                            return ToolResult.text("reconnection triggered");
                        });

        registerVersionSpecificTools(server);
    }

    /**
     * Registers tools that are needed only for a specific protocol version's conformance suite;
     * no-op by default. Overridden by version-specific subclasses.
     */
    protected void registerVersionSpecificTools(ServerEngine server) {}

    /**
     * Registers tools that exercise input-required results for elicitation, sampling, roots listing,
     * request-state handling, multi-round input, tamper detection, and client capability negotiation.
     * Draft-only (SEP-2322, protocol version 2026-07-28): called by {@code EdgeConformanceServer}.
     */
    protected void registerInputRequiredTools(ServerEngine server) {
        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_elicitation")
                        .description("SEP-2322 elicitation InputRequiredResult")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var inputResponses = request.inputResponses();
                        if (inputResponses != null && inputResponses.containsKey("user_name")) {
                            var resp = inputResponses.get("user_name");
                            if (resp instanceof Map<?, ?>) {
                                var content = field(resp, "content");
                                var name = stringField(content, "name", "World");
                                return ToolResult.text("Hello, " + name + "!");
                            }
                        }
                        return ToolResult.inputRequired(
                                Map.of("user_name", buildFormElicitation("What is your name?", "name", "string")),
                                null);
                    }
                });

        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_sampling")
                        .description("SEP-2322 sampling InputRequiredResult")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var inputResponses = request.inputResponses();
                        if (inputResponses != null && inputResponses.containsKey("capital_question")) {
                            var resp = inputResponses.get("capital_question");
                            var text = stringField(field(resp, "content"), "text", "done");
                            return ToolResult.text(text);
                        }
                        return ToolResult.inputRequired(
                                Map.of("capital_question", buildSamplingRequest("What is the capital of France?")),
                                null);
                    }
                });

        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_list_roots")
                        .description("SEP-2322 roots/list InputRequiredResult")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var inputResponses = request.inputResponses();
                        if (inputResponses != null && inputResponses.containsKey("client_roots")) {
                            return ToolResult.text("Roots received");
                        }
                        return ToolResult.inputRequired(Map.of("client_roots", buildRootsListRequest()), null);
                    }
                });

        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_request_state")
                        .description("SEP-2322 requestState round-trip")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var inputResponses = request.inputResponses();
                        var requestState = request.requestState();
                        if (inputResponses != null && inputResponses.containsKey("confirm") && requestState != null) {
                            return ToolResult.text("state-ok");
                        }
                        return ToolResult.inputRequired(
                                Map.of("confirm", buildFormElicitation("Please confirm", "ok", "boolean")),
                                "opaque-server-state");
                    }
                });

        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_multiple_inputs")
                        .description("SEP-2322 multiple inputRequests")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var inputResponses = request.inputResponses();
                        if (inputResponses != null
                                && inputResponses.containsKey("user_name")
                                && inputResponses.containsKey("greeting")
                                && inputResponses.containsKey("client_roots")) {
                            return ToolResult.text("All inputs received");
                        }
                        var inputRequests = new LinkedHashMap<String, InputRequest>();
                        inputRequests.put("user_name", buildFormElicitation("What is your name?", "name", "string"));
                        inputRequests.put("greeting", buildSamplingRequest("Generate a greeting"));
                        inputRequests.put("client_roots", buildRootsListRequest());
                        return ToolResult.inputRequired(inputRequests, "multi-input-state");
                    }
                });

        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_multi_round")
                        .description("SEP-2322 multi-round InputRequiredResult")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var inputResponses = request.inputResponses();
                        var requestState = request.requestState();
                        if (inputResponses != null && inputResponses.containsKey("step2")) {
                            var color = stringField(field(inputResponses.get("step2"), "content"), "color", "unknown");
                            return ToolResult.text("Done with color: " + color);
                        }
                        if (inputResponses != null
                                && inputResponses.containsKey("step1")
                                && "state-round-1".equals(requestState)) {
                            return ToolResult.inputRequired(
                                    Map.of(
                                            "step2",
                                            buildFormElicitation(
                                                    "Step 2: What is your favorite color?", "color", "string")),
                                    "state-round-2");
                        }
                        return ToolResult.inputRequired(
                                Map.of("step1", buildFormElicitation("Step 1: What is your name?", "name", "string")),
                                "state-round-1");
                    }
                });

        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_tampered_state")
                        .description("SEP-2322 tampered requestState rejection")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var inputResponses = request.inputResponses();
                        var requestState = request.requestState();
                        if (inputResponses != null && inputResponses.containsKey("confirm")) {
                            if (!verifyState(requestState)) {
                                throw new IllegalArgumentException("Invalid or tampered requestState");
                            }
                            return ToolResult.text("State verified");
                        }
                        return ToolResult.inputRequired(
                                Map.of("confirm", buildFormElicitation("Please confirm", "ok", "boolean")),
                                signState("tamper-check"));
                    }
                });

        registerHandler(
                server,
                new AbstractToolHandler(ToolDescriptor.builder()
                        .name("test_input_required_result_capabilities")
                        .description("SEP-2322 respect client capabilities")
                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                        .build()) {
                    @Override
                    public ToolResult handle(InteractionContext ctx, ToolRequest request) {
                        var meta = request.meta();
                        var capabilities = meta != null ? meta.get("io.modelcontextprotocol/clientCapabilities") : null;
                        var hasSampling = field(capabilities, "sampling") != null;
                        var hasElicitation = field(capabilities, "elicitation") != null;
                        var inputRequests = new LinkedHashMap<String, InputRequest>();
                        if (hasSampling) {
                            inputRequests.put("ai_response", buildSamplingRequest("Generate a helpful response"));
                        }
                        if (hasElicitation) {
                            inputRequests.put(
                                    "user_name", buildFormElicitation("What is your name?", "name", "string"));
                        }
                        if (inputRequests.isEmpty()) {
                            return ToolResult.text("No capabilities declared");
                        }
                        return ToolResult.inputRequired(inputRequests, null);
                    }
                });
    }

    /**
     * Registers static resources and a URI template resource for conformance testing.
     */
    private void registerResources(ServerEngine server) {
        server.resources()
                .register(
                        ResourceDescriptor.of("hello", "hello://world", "Hello resource", "text/plain"),
                        (ctx, request) -> TextResourceContents.of(request.uri(), "Hello, World!", "text/plain"));

        server.resources()
                .register(
                        ResourceDescriptor.of(
                                "static-text", "test://static-text", "Static text resource", "text/plain"),
                        (ctx, request) -> TextResourceContents.of(
                                request.uri(), "This is static text content for testing.", "text/plain"));

        server.resources()
                .register(
                        ResourceDescriptor.of(
                                "static-binary", "test://static-binary", "Static binary resource", "image/png"),
                        (ctx, request) -> BlobResourceContents.of(request.uri(), MINI_PNG_BYTES, "image/png"));

        server.resources()
                .registerTemplate(
                        builder -> builder.name("test-template")
                                .uriTemplate("test://template/{id}/data")
                                .description("test-description"),
                        (ctx, request) -> TextResourceContents.of(
                                request.uri(),
                                "Resource content for id: "
                                        + request.params().get("id").scalarValue(),
                                "text/plain"));
    }

    private void registerPrompts(ServerEngine server) {
        server.prompts()
                .register(PromptDescriptor.of("greeting", "A greeting prompt"), List.of(PromptMessage.user("Hello!")))
                .register(
                        PromptDescriptor.of("test_simple_prompt", "A simple test prompt"),
                        List.of(PromptMessage.user("This is a simple test prompt.")))
                .register(
                        PromptDescriptor.of("test_prompt_with_arguments", "A parameterized prompt"),
                        (ctx, request) -> PromptResult.messages(List.of(PromptMessage.user(
                                "Prompt with arguments: " + request.arguments().json()))));

        server.prompts()
                .register(
                        PromptDescriptor.of("test_prompt_with_embedded_resource", "Prompt with embedded resource"),
                        List.of(PromptMessage.user(EmbeddedResource.of(TextResourceContents.of(
                                "test://embedded-resource-content",
                                "Embedded resource content for testing.",
                                "text/plain")))));

        server.prompts()
                .register(
                        PromptDescriptor.of("test_prompt_with_image", "Prompt with image"),
                        List.of(PromptMessage.user(ImageContent.of(MINI_PNG_BYTES, "image/png"))));

        registerVersionSpecificPrompts(server);
    }

    /**
     * Registers prompts that are needed only for a specific protocol version's conformance suite;
     * no-op by default. Overridden by version-specific subclasses.
     */
    protected void registerVersionSpecificPrompts(ServerEngine server) {}

    private static void registerHandler(ServerEngine server, ToolHandler handler) {
        server.tools().registerAsync(handler.descriptor(), handler::handleAsync);
    }

    protected static @Nullable Object field(@Nullable Object value, String name) {
        return value instanceof Map<?, ?> map ? map.get(name) : null;
    }

    private static String stringField(@Nullable Object value, String name, String defaultValue) {
        var field = field(value, name);
        return field instanceof String text ? text : defaultValue;
    }
}
