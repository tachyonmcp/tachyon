---
title: "FAQ"
weight: 95
sidebar_order: 95
toc: true
description: |-
  Answers to common questions about Tachyon: Java versions, frameworks, concurrency, deployment, and compatibility.
---

Answers to the questions Java teams usually ask before adopting Tachyon. For a runnable server, start with the [Quickstart](quickstart.md).

## Choosing Tachyon

### What does Tachyon require?

Tachyon requires Java 21 or newer. Add `dev.tachyonmcp:tachyon-core` to get the Java API, Netty transport, Jackson 3 integration, and default JSON Schema validation.

### Do I need Spring Boot or a reactive framework?

No. Tachyon starts its own Netty-based Streamable HTTP server. You can run it as a standalone service or manage its start and close operations from your framework's application lifecycle.

### Can I use Tachyon with Spring Boot, Quarkus, or Micronaut?

Yes. Keep your application services in the framework container and inject them into Tachyon handlers. Tachyon doesn't require a servlet container or reactive types.

### Which MCP versions and transports are supported?

Tachyon negotiates MCP 2025-11-25 and 2026-07-28 and passes the official conformance suites for both. It serves MCP over Streamable HTTP; HTTP/2 transports aren't currently supported.

## Writing handlers

### Can a handler perform blocking I/O?

Yes. Synchronous handlers run on virtual threads, away from the Netty event loop. You can call blocking database drivers and HTTP clients without introducing a reactive pipeline.

### When should I use an asynchronous handler?

Use `registerAsync` when your dependency already returns a
`CompletionStage`. For ordinary blocking Java code, use the synchronous API and let Tachyon run it on a virtual thread. See [Tools](features/tools.md#async-tool).

### Does my application have to use Jackson?

No. `JsonDocument`, `JsonObject`, `JsonArray`, and `JsonSchema` provide a neutral API. Tachyon uses Jackson 3 by default, supports kotlinx.serialization through `tachyon-kotlin`, and lets you supply a custom `PayloadSerde`. See [JSON and JSON Schema](json.md).

### How are tool arguments and results validated?

Attach JSON Schema to a tool descriptor. Tachyon validates input and structured output, using JSON Schema 2020-12 when the schema doesn't declare another dialect. You can replace or disable either validator through [JSON configuration](json.md#configure-schema-validation).

### How do I keep Kotlin tool schemas in sync with data classes?

Generate input and output schemas from Kotlin model classes instead of maintaining JSON strings.
The [kt-schema integration guide](kotlin/kt-schema-json.md) shows the complete request and structured
response path from the runnable Weather MCP Kotlin example.

### How do I report a tool failure?

Return `ToolResult.error(message)` for an expected tool-level failure. Reserve thrown exceptions for unexpected failures that the server should map to a JSON-RPC error. See
[returning tool results](features/tools.md#return-results).

## Deployment and operations

### Is the server stateless?

Yes, by default. Stateless mode avoids server-side session affinity and is the simplest option for horizontal scaling.

### When should I enable sessions?

Enable sessions when you need resumable SSE streams, `Last-Event-ID` replay, or session-scoped state. Live sessions stay in-process, so clustered deployments need sticky routing while a session is active. Durable `SessionStore` and `SessionEventStore` implementations enable restart recovery, not distributed live-session ownership. See
[session configuration](running/configuration.md#session).

### How should I deploy long-running tools?

Send progress notifications or SSE comments so the response becomes a live SSE stream, then set the heartbeat interval below any proxy or load-balancer idle timeout. See [keep-alive for long-running tools](running/configuration.md#keep-alive-for-long-running-tools).

### Does shutdown wait for active handlers?

Yes. `close()` stops accepting connections, rejects new requests with `503`, and waits for admitted requests to finish for up to `shutdownGracePeriod`, five seconds by default. When that period ends, Tachyon interrupts the threads that run synchronous handlers. Interruption is cooperative: a handler that ignores it keeps running, and work Tachyon doesn't own, such as a `CompletionStage` on your own executor or an external workflow, isn't cancelled. Set `shutdownGracePeriod` to fit your platform's termination window. See [Runtime](running/configuration.md#runtime).

### How do I test a server without reserving a port?

Configure port `0`; the operating system assigns a free port. Exercise the running server through an MCP client for end-to-end coverage, or follow the curl flow in the [Quickstart](quickstart.md).

## Compatibility and next steps

### Will an MCP protocol upgrade break my handlers?

Tachyon keeps function and descriptor APIs separate from its internal protocol mappers. A new wire
version normally changes the mapper rather than your tool, resource, prompt, or completion function.

### Is Kotlin supported?

Yes. The `tachyon-kotlin` module adds a coroutine-first DSL and kotlinx.serialization integration over the Java API. See the [Kotlin DSL guide](kotlin/).

### Where do I start?

Build the [Quickstart](quickstart.md), then choose the guide for [tools](features/tools.md), [resources](features/resources.md), [tasks](features/tasks.md), or
[extensions](extensions/).
