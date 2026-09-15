---
title: "Client interactions"
weight: 19
sidebar_order: 19
toc: true
description: |-
  Request user input from a handler and understand Tachyon's sampling compatibility boundary.
---

An MCP server can ask its connected client for user input. Tachyon exposes typed form elicitation
through `InteractionContext.client()`. Sampling is a legacy protocol feature ([SEP-2577](https://modelcontextprotocol.io/seps/2577-deprecate-roots-sampling-and-logging)) and isn't part of the
current, non-deprecated API guidance.

## Request form input from an annotated handler

Add an `InteractionContext` parameter to an annotated tool, resource, or prompt method. Tachyon
injects it without adding a client argument. Call `context.client().elicitation().create(...)` and
define the accepted response with a restricted JSON Schema whose top-level properties are primitive
values.

```java
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.ElicitationRequest;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;

class CityTools {
    private static final JsonSchema CITY_SCHEMA = JsonSchema.unchecked("""
            {
              "type": "object",
              "properties": {
                "city": { "type": "string" }
              },
              "required": ["city"]
            }
            """);

    @McpTool(name = "choose-city", description = "Ask the user to choose a forecast city")
    public ToolResult chooseCity(InteractionContext context) {
        var result = context.client().elicitation().create(
                ElicitationRequest.builder()
                        .message("Choose a forecast city")
                        .requestedSchema(CITY_SCHEMA)
                        .build()).join();

        return switch (result.action()) {
            case ACCEPT -> ToolResult.text("Selected " + result.content().stringValue("city"));
            case DECLINE -> ToolResult.error("City selection declined");
            case CANCEL -> ToolResult.error("City selection cancelled");
        };
    }
}

var server = TachyonServer.builder()
        .session(session -> session.enabled(true))
        .annotations(annotations -> annotations.register(new CityTools()))
        .port(8080)
        .build();
```

The [Quickstart](../quickstart.md) covers project setup, including `-parameters` for methods with
named arguments. See [Annotations](../annotations.md) for context injection and result mapping.

`ElicitationRequest` and `ElicitationResult` are immutable interfaces. Use `builder()` to construct
them, `builder().from(existing)` to copy their values, or the `of(...)` convenience factories.

The returned action is `ACCEPT`, `DECLINE`, or `CANCEL`. `content()` is present only for `ACCEPT`.
Check the action before reading it.

Immediate server-to-client requests require a stateful session and an MCP 2025-11-25 client.
The client must advertise form elicitation and keep a bidirectional connection available for the
round trip. For stateless MCP 2026-07-28 clients, return an input-required result as shown below.
Treat rejection, disconnection, and request timeout as normal handler failure paths.

## Return an input-required result

Tools and prompts can instead return an input-required result. In `CityTools.chooseCity`, replace
the elicitation call and switch with the return below. This ends the current handler call
with one or more input requests rather than waiting for an immediate client response.

```java
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import java.util.Map;

return ToolResult.inputRequired(
        Map.of("city", FormInputRequest.of("Choose a forecast city", CITY_SCHEMA)),
        "forecast-draft-42");
```

Use the optional state string as an opaque correlation value. For long-running task workflows,
put the same `InputRequestBundle` on an `INPUT_REQUIRED` task snapshot and accept the submitted
values through `TaskConnector.update(...)`. See [Tasks](tasks.md).

## Programmatic handlers

Programmatic handlers receive the same context. With a `CityTools` instance, the equivalent
registration is:

```java
var cityTools = new CityTools();
server.tools().register(
        tool -> tool.name("choose-city").description("Ask the user to choose a forecast city"),
        (context, request) -> cityTools.chooseCity(context));
```

Use this in place of the annotation registration when configuring the tool through its descriptor.

## Sampling status

MCP 2026-07-28 deprecates `sampling/createMessage` through SEP-2577. Tachyon retains
`ClientContext.sampling()` only for compatibility with older clients, and both it and
`SamplingService` are marked `@LegacyApi`.

Don't add sampling to new integrations. An application that must support an older MCP client
should isolate sampling behind a version-specific adapter and plan its removal.

## Executable examples

[DeclarativeElicitationTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeElicitationTest.java) uses `CityTools.chooseCity` above
with a real MCP SDK client and a stateful server. It checks the requested form and all three client
actions: accept, decline, and cancel.
[DeclarativeResultsTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeResultsTest.java) checks the alternative input-required
response on MCP 2026-07-28, including the form schema and `forecast-draft-42` request state.
