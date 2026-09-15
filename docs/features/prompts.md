---
title: "Prompts"
weight: 17
sidebar_order: 17
toc: true
description: |-
  Declare MCP prompts with annotations, bind arguments, and return reusable messages with Java or Kotlin.
---

Prompts let clients request reusable message templates. Annotate a method to declare its arguments
and turn its return value into messages.

## Declare a prompt with annotations

```java
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.core.server.TachyonServer;
import org.jspecify.annotations.Nullable;

class ReviewPrompts {
    @McpPrompt(name = "review-code", description = "Review code for a selected concern")
    public String review(String concern, @Nullable String language) {
        return "Review this code for " + concern
                + (language == null ? "" : " in " + language) + ".";
    }
}

var server = TachyonServer.builder()
        .annotations(annotations -> annotations.register(new ReviewPrompts()))
        .port(8080)
        .build();
```

Compile with `-parameters` as shown in the [Quickstart](../quickstart.md). Tachyon advertises
`concern` as required and `language` as optional. Named scalar parameters become prompt arguments;
use JSpecify `@Nullable` or `Optional` for optional values. An `InteractionContext` parameter is
injected and never advertised. The prompt name defaults to the method name when `name` is omitted.

### Return messages

A string becomes one user message. Set `@McpPrompt(role = Role.ASSISTANT)` to generate an assistant
message, importing `dev.tachyonmcp.api.server.domain.Role`. Return a `PromptMessage`, a list of
messages, or a `PromptResult` for full control; explicit messages retain their own roles:

```java
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import java.util.List;

@McpPrompt(name = "review-conversation")
public List<PromptMessage> conversation(String concern) {
    return List.of(
            PromptMessage.user("Review this code for " + concern + "."),
            PromptMessage.of(Role.ASSISTANT, TextContent.of("Share the code you want reviewed.")));
}
```

Add this method to your service. Prompt results and list elements must be non-null. Enum arguments
get [automatic completions](completions.md#complete-enum-values-automatically); use `@McpCompletion`
for values supplied by your application. See the [annotation reference](../annotations.md) for
all supported return types and binding rules.

## Programmatic registration

Use descriptors for argument descriptions, explicit input-schema constraints, or dynamic
registration. Both styles use the same prompt registry.

### Register a prompt

```java
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.core.server.TachyonServer;

var server = TachyonServer.builder()
        .withPrompts(prompts -> prompts.register(
                prompt -> prompt
                        .name("review-code")
                        .description("Review code for a selected concern")
                        .addArguments(PromptArgument.of(
                                "concern", "Concern", "Security, performance, or clarity", true)),
                (context, request) -> {
                    var concern = request.arguments().stringValue("concern");
                    return PromptResult.messages(PromptMessage.user(
                            "Review this code for " + concern + "."));
                }))
        .port(8080)
        .build();
```

Use `PromptArgument.required()` to tell clients whether an argument is required. Use
`PromptDescriptor.inputSchema()` when you need constraints that the argument list can't express.

### Use asynchronous work

Synchronous prompt handlers run on virtual threads. Register an `AsyncPromptFn` with
`registerAsync(...)` when your dependency already returns `CompletionStage`.

```java
server.prompts().registerAsync(
        descriptor,
        (context, request) -> promptService.create(request.arguments())
                .thenApply(message -> PromptResult.messages(PromptMessage.user(message))));
```

## Use the Kotlin DSL

Kotlin handlers are suspending functions:

```kotlin
prompt(
    name = "review-code",
    description = "Review code for a selected concern",
    arguments = listOf(
        PromptArgument.of("concern", "Concern", "Security, performance, or clarity", true),
    ),
) {
    listOf(PromptMessage.user("Review this code for ${arguments.stringValue("concern")}."))
}
```

## Executable examples

[DeclarativeFeaturesTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeFeaturesTest.java) exercises prompt argument discovery,
rendering, and annotation roles.
[DeclarativeResultsTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeResultsTest.java) uses `conversation` above and verifies
that its user and assistant messages retain their roles and text over HTTP.
