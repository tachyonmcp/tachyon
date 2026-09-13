---
title: integrations
tags: [module, integrations]
sources: [integrations/pom.xml, integrations/tachyon-annotations-mcp-java/, integrations/tachyon-annotations-langchain4j/, integrations/tachyon-annotations-spring-ai/, integrations/tachyon-opentelemetry/, integrations/tachyon-tasks-temporal/]
updated: 2026-09-13
commit: 582f9c52
---

# 🔌 integrations

Verdict: 5 tracked modules (`integrations/pom.xml:23-27`), each implements one core SPI. No core changes needed to add another.

| Module | SPI implemented | Entry | Proof |
|---|---|---|---|
| `tachyon-annotations-mcp-java` | `AnnotationProvider` | `McpJavaAnnotationProvider.instance()` — scans official MCP Java SDK annotations (`@Tool`, `@Resource`, …) | `integrations/tachyon-annotations-mcp-java/src/main/java/dev/tachyonmcp/annotations/mcpjava/McpJavaAnnotationProvider.java:58-68` |
| `tachyon-annotations-langchain4j` | `AnnotationProvider` | `new LangChain4jAnnotationProvider()` — LangChain4j `@Tool` | `integrations/tachyon-annotations-langchain4j/src/main/java/dev/tachyonmcp/annotations/langchain4j/LangChain4jAnnotationProvider.java:46-55` |
| `tachyon-annotations-spring-ai` | `AnnotationProvider` | `SpringAiAnnotationProvider.instance()` — Spring AI `@McpTool/@McpResource/@McpPrompt`; `@McpResource` needs non-blank uri | `integrations/tachyon-annotations-spring-ai/src/main/java/dev/tachyonmcp/annotations/spring/ai/SpringAiAnnotationProvider.java:65-85` |
| `tachyon-opentelemetry` | `ObservationListener` | `McpOpenTelemetryListener.create(otel)` | [[observability]] |
| `tachyon-tasks-temporal` | `TaskConnector` | `TemporalTaskExecutionEngine.builder(client).taskQueue(..).route(..).build().connector()` | [[tasks]] |

## 🏷️ Annotation flow

`ServerBuilder.annotations(a -> a.withProvider(p).register(obj))` → after server construction `DefaultServerBuilder.applyAnnotationRegistrations` creates `DefaultAnnotationRegistrationContext(tools, resources, prompts, completions, serializer, deserializer)` → `provider.register(instance, ctx)` → provider calls normal façades. Shared reflection helper `AnnotationInvocationSupport` in api `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java`.

## 📚 Examples (not modules of main reactor)

`examples/`: `weather-mcp`, `weather-mcp-kotlin`, `echo-kotlin`, `mcp-java`, `langchain4j-mcp`, `mcp-skills`, `temporal`. Built by `make examples` (published artifacts) / `make examples-snapshot`.