---
title: "Completions"
weight: 18
sidebar_order: 18
toc: true
description: |-
  Use annotations to suggest MCP prompt arguments and resource-template variables, including automatic enum completions.
---

Completions help clients suggest values while a user fills a prompt argument or resource-template
variable. Register each handler against the exact prompt name or resource template advertised by
your server.

## Complete a prompt argument with annotations

Put the prompt and its completion in one service. `@McpCompletion` names the target prompt; its first
string parameter names the argument being completed and receives the current partial text.

```java
import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.List;
import java.util.Locale;

class ReviewService {
    @McpPrompt(name = "review-code")
    public String review(String concern) {
        return "Review this code for " + concern + ".";
    }

    @McpCompletion(prompt = "review-code")
    public List<String> concerns(String concern) {
        var prefix = concern.toLowerCase(Locale.ROOT);
        return List.of("clarity", "performance", "security").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }
}

var server = TachyonServer.builder()
        .annotations(annotations -> annotations.register(new ReviewService()))
        .port(8080)
        .build();
```

Compile with `-parameters` as shown in the [Quickstart](../quickstart.md). Requests to complete
arguments other than `concern` return no candidates without calling this method. Return
`CompletionResult` instead of `List<String>` when you need `total`, `hasMore`, or metadata.

### Complete a resource variable

Add a resource template and its completion to a service. Match the exact URI template in
`resource`, and match its variable name in the parameter:

```java
import dev.tachyonmcp.api.annotations.McpResource;

@McpResource(uri = "weather://current/{city}")
public String weather(String city) {
    return "Forecast for " + city;
}

@McpCompletion(resource = "weather://current/{city}")
public List<String> cities(String city) {
    var prefix = city.toLowerCase(Locale.ROOT);
    return List.of("London", "Paris", "Prague").stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
            .toList();
}
```

Specify exactly one of `prompt` or `resource`. The target may also be registered by another service.

### Use sibling arguments

Later scalar parameters receive values the client has already selected. Mark a sibling optional
when it may not have been filled in yet:

```java
@McpPrompt(name = "trip")
public String trip(String city, @org.jspecify.annotations.Nullable String country) {
    return "Plan a trip to " + city + (country == null ? "" : ", " + country);
}

@McpCompletion(prompt = "trip")
public List<String> citiesForTrip(String city, @org.jspecify.annotations.Nullable String country) {
    var candidates = "France".equals(country)
            ? List.of("Paris", "Lyon") : List.of("London", "Paris", "Lyon");
    return candidates.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(city.toLowerCase(Locale.ROOT)))
            .toList();
}
```

To complete several arguments on one target, take one `CompletionRequest` instead of named
parameters and dispatch on `argumentName()`. It also exposes `argumentValue()`, `resolvedArguments()`,
and request metadata. Either signature can include an injected `InteractionContext`. Declare one
completion method per target in a service; don't mix a full request with named parameters. See the
[annotation reference](../annotations.md#argument-completions) for a full-request example.

### Complete enum values automatically

Use an enum when the allowed values are fixed. This prompt gets completion candidates from its enum
constant names without an `@McpCompletion` method:

```java
public enum Concern { CLARITY, PERFORMANCE, SECURITY }

@McpPrompt(name = "review-by-concern")
public String reviewByConcern(Concern concern) {
    return "Review this code for " + concern + ".";
}
```

The same applies to enum resource-template variables and optional enum arguments. Completion uses a
case-insensitive prefix; actual argument values use exact constant names. An explicit
`@McpCompletion` for the same target in the same service replaces automatic enum completion.

## Programmatic registration

Register a handler directly for dynamic targets or dependencies returning `CompletionStage`.

### Complete a prompt argument

```java
import dev.tachyonmcp.api.server.features.completions.CompletionResult;

server.completions().registerForPrompt("review-code", (context, request) -> {
    if (!request.argumentName().equals("concern")) {
        return CompletionResult.empty();
    }

    var prefix = request.argumentValue().toLowerCase(Locale.ROOT);
    var matches = List.of("clarity", "performance", "security").stream()
            .filter(value -> value.startsWith(prefix))
            .toList();
    return CompletionResult.of(matches);
});
```

`argumentValue()` contains the partial value. `resolvedArguments()` contains sibling arguments the
client has already selected. Return `CompletionResult.empty()` for argument names you don't handle.

### Complete a resource variable

Use the exact URI template string, not the resource name:

```java
server.completions().registerForResourceAsync(
        "weather://current/{city}",
        (context, request) -> cityService.search(request.argumentValue())
                .thenApply(CompletionResult::of));
```

Tachyon returns an empty completion result when no handler matches a reference. It limits the wire
response to 100 values and sets `hasMore` when it truncates a larger result.

## Use the Kotlin DSL

```kotlin
promptCompletion("review-code") {
    if (request.argumentName() != "concern") {
        CompletionResult.empty()
    } else {
        CompletionResult.of(
            listOf("clarity", "performance", "security")
                .filter { it.startsWith(request.argumentValue(), ignoreCase = true) },
        )
    }
}

resourceCompletion("weather://current/{city}") {
    CompletionResult.of(cityService.search(request.argumentValue()))
}
```

## Executable examples

[DeclarativeCompletionsTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeCompletionsTest.java) exercises partial values,
optional siblings, resource references, full requests, and result metadata on both supported protocol
versions. [DeclarativeFeaturesTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeFeaturesTest.java) checks automatic enum
completions and explicit overrides. The
[Spring Boot weather service](https://github.com/tachyonmcp/tachyon/blob/main/examples/weather-mcp-spring-boot/src/main/java/com/example/weather/WeatherService.java)
combines annotated tools, resources, prompts, and city completions in a runnable application.
