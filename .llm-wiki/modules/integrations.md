---
title: integrations
tags: [module, integrations]
sources: [integrations/pom.xml, integrations/tachyon-annotations-mcp-java/, integrations/tachyon-annotations-langchain4j/, integrations/tachyon-annotations-spring-ai/, integrations/tachyon-opentelemetry/, integrations/tachyon-tasks-temporal/, integrations/tachyon-spring-boot-starter/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java]
updated: 2026-09-14
commit: 70e205dc
---

# 🔌 integrations

Verdict: 6 tracked modules (`integrations/pom.xml:23-28`). Annotation providers + OTel + Temporal implement one core SPI each; Spring Boot starter wires `ServerBuilder` from beans. No core changes needed to add another.

| Module | SPI implemented | Entry | Proof |
|---|---|---|---|
| `tachyon-annotations-mcp-java` | `AnnotationProvider` | `McpJavaAnnotationProvider.instance()` — scans official MCP Java SDK annotations (`@Tool`, `@Resource`, …) | `integrations/tachyon-annotations-mcp-java/src/main/java/dev/tachyonmcp/annotations/mcpjava/McpJavaAnnotationProvider.java:58-68` |
| `tachyon-annotations-langchain4j` | `AnnotationProvider` | `new LangChain4jAnnotationProvider()` — LangChain4j `@Tool` | `integrations/tachyon-annotations-langchain4j/src/main/java/dev/tachyonmcp/annotations/langchain4j/LangChain4jAnnotationProvider.java:46-55` |
| `tachyon-annotations-spring-ai` | `AnnotationProvider` | `SpringAiAnnotationProvider.instance()` — Spring AI `@McpTool/@McpResource/@McpPrompt`; `@McpResource` needs non-blank uri | `integrations/tachyon-annotations-spring-ai/src/main/java/dev/tachyonmcp/annotations/spring/ai/SpringAiAnnotationProvider.java:65-85` |
| `tachyon-opentelemetry` | `ObservationListener` | `McpOpenTelemetryListener.create(otel)` | [[observability]] |
| `tachyon-tasks-temporal` | `TaskConnector` | `TemporalTaskExecutionEngine.builder(client).taskQueue(..).route(..).build().connector()` | [[tasks]] |
| `tachyon-spring-boot-starter` | Boot 4 auto-config | `TachyonAutoConfiguration` → `TachyonServer` bean + `TachyonServerLifecycle` (SmartLifecycle start/close) | `integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java:42-64` |

## 🏷️ Annotation flow

`ServerBuilder.annotations(a -> a.register(obj))` → after server construction `DefaultServerBuilder.applyAnnotationRegistrations` creates `DefaultAnnotationRegistrationContext(tools, resources, prompts, completions, serializer, deserializer)` → `provider.register(instance, ctx)` → provider calls normal façades. Shared reflection helper `AnnotationInvocationSupport` in api `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java`.

- 🎯 Default provider = core `TachyonAnnotationProvider` (api `@McpTool/@McpResource/@McpPrompt` in `dev.tachyonmcp.api.annotations`) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java:38`. `withProvider(p)` switches.
- Binding: `MethodInvoker.forTool` — single record/POJO/Map param ⇒ whole args, else named params (`-parameters` required, `Optional`/`@Nullable` ⇒ optional) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:52-66`. Missing required ⇒ `InvalidArgumentException`.
- Results: `ResultMappers` (tool/resource/prompt) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java`. Object return ⇒ `outputSchema = JsonSchema.generate(returnType)`. Prompt messages built from `String`/`ContentBlock`/object take `@McpPrompt.role()` (default `USER`); `PromptMessage` keeps its own role `ResultMappers.java:50-75`.
- Fail fast at `build()`: multi-annotation, duplicate name/URI, private, static resource with args, template var mismatch `TachyonAnnotationProvider.java:84-150`.
- `TachyonAnnotationProvider.declaresFeatures(Class)` lets DI containers pick beans by type without instantiating — Spring starter uses it with `getType(name, false)` + `ClassUtils.getUserClass`.

## 🌱 Spring Boot starter

- `tachyon.*` → `TachyonProperties` record (enabled, name, version, host, port=8080).
- Registers: annotated singleton beans, `ServerExtension` beans, then `TachyonServerCustomizer` beans (ordered, last word).
- ⚠️ Annotated bean depending on `TachyonServer` ⇒ Spring cycle (server built from those beans).
- Spring Boot `4.1.1` pinned in module pom only; no Boot BOM import (would override Netty/Jackson/JUnit).

## 📚 Examples (not modules of main reactor)

`examples/`: `weather-mcp`, `weather-mcp-kotlin`, `echo-kotlin`, `mcp-java`, `langchain4j-mcp`, `mcp-skills`, `temporal`. Built by `make examples` (published artifacts) / `make examples-snapshot`.
