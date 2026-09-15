---
title: "MCP features"
overview_title: "Choose a feature"
weight: 10
sidebar_order: 10
toc: true
description: |-
  Add tools, resources, prompts, completions, client interactions, and long-running tasks.
---

MCP features define what clients can discover and call on your server. Start with the feature that
matches your application behaviour; you don't need to enable features you don't use.

## Declare features with annotations

Start with plain Java methods annotated with `@McpTool`, `@McpResource`, `@McpPrompt`, or
`@McpCompletion`. Tachyon binds arguments and maps return values to MCP responses. Register your
service instances once:

```java
var server = TachyonServer.builder()
        .annotations(annotations -> annotations.register(new GreetingService()))
        .port(8080)
        .build();
```

`GreetingService` is your class containing annotated methods. Annotations are included with
`tachyon-core`; no extra integration module is needed. Compile Java with `-parameters` so method
parameter names become MCP argument names. The [Quickstart](../quickstart.md) includes the build
configuration and a complete service. The [annotation reference](../annotations.md) covers shared
binding rules and registration; the [Spring Boot starter](../spring-boot.md) discovers service beans
automatically.

Each guide starts with annotations. Use programmatic registration for dynamic descriptors, custom
schemas, or APIs that already return `CompletionStage`. Both styles share the same feature registries
and can be used in one server.

## Expose server capabilities

- [Tools](tools/) — run operations with validated arguments and structured results
- [Resources](resources/) — expose text or binary content through fixed and templated URIs
- [Prompts](prompts/) — produce reusable messages from declared arguments
- [Completions](completions/) — suggest prompt argument and resource variable values

## Continue work across interactions

- [Client interactions](client-interactions/) — request user input from an active handler
- [Tasks](tasks/) — connect tool calls to durable work owned by a job or workflow system

Tachyon advertises tools, resources, and prompts automatically after you register them. Configure
capability modes explicitly only when you need dynamic registration or notifications. See
[Configuration](../running/configuration/#capabilities).
