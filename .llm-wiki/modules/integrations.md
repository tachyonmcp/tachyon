---
title: integrations
tags: [module, integrations]
sources: [integrations/pom.xml, integrations/tachyon-annotations-mcp-java/, integrations/tachyon-annotations-langchain4j/, integrations/tachyon-annotations-spring-ai/, integrations/tachyon-opentelemetry/, integrations/tachyon-tasks-temporal/, integrations/tachyon-spring-boot-starter/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java]
updated: 2026-09-14
commit: 8c7738c0
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
| `tachyon-spring-boot-starter` | Boot 4 auto-config | `TachyonAutoConfiguration` → server, deferred `TachyonBeanRegistrar`, `TachyonServerLifecycle` | `integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java:30` |

## 🏷️ Annotation flow

`ServerBuilder.annotations(a -> a.register(obj))` → `DefaultServerBuilder.applyAnnotationRegistrations` delegates to `server.annotations(...)` (`DefaultServerBuilder.java:264`). `DefaultTachyonServer.annotations` creates a fresh registration context from its registries and configured codecs (`DefaultTachyonServer.java:1044`). Providers register normal façade handlers. Same path works after construction, before transport starts.

- 🎯 Default provider = core `TachyonAnnotationProvider` (api `@McpTool/@McpResource/@McpPrompt` in `dev.tachyonmcp.api.annotations`) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java:38`. `withProvider(p)` switches.
- Binding: `MethodInvoker.forTool` — single record/POJO/Map param ⇒ whole args, else named params (`-parameters` required, `Optional`/`@Nullable` ⇒ optional) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:62`. Missing required ⇒ `InvalidArgumentException`.
- Results: `ResultMappers` (tool/resource/prompt) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java`. Object return ⇒ `outputSchema = JsonSchema.generate(returnType)`. Prompt messages built from `String`/`ContentBlock`/object take `@McpPrompt.role()` (default `USER`); `PromptMessage` keeps its own role `ResultMappers.java:50-75`.
- Fail fast at registration: multi-annotation, duplicate name/URI, private, static resource with args, template var mismatch `TachyonAnnotationProvider.java:95`.
- `TachyonAnnotationProvider.register(instance, annotatedType, context)` separates metadata from receiver (`TachyonAnnotationProvider.java:91`). `MethodInvoker.invocationMethod` resolves proxy-visible methods once at registration; invocation stays on the proxy (`MethodInvoker.java:49`).

## 🌱 Spring Boot starter

- `tachyon.*` → `TachyonProperties` record (enabled, name, version, host, port=8080).
- Builds server with properties, ordered extensions and customizers, then registers annotated beans after singleton initialization, before lifecycle startup (`TachyonAutoConfiguration.java:48`, `TachyonBeanRegistrar.java:20`). Annotated beans can inject `TachyonServer`.
- Discovery uses `AopUtils.getTargetClass` on initialized beans; JDK and class proxies retain advice. Unrelated lazy beans stay uninitialized; lazy annotated beans need discoverable declared types (`TachyonBeanRegistrar.java:21`).
- User server ⇒ construction and registrar back off together; lifecycle still supplied (`TachyonAutoConfiguration.java:31`, `:69`).
- Wire regression tests cover all three feature kinds through JDK proxy advice, server injection, custom codecs, lazy-bean isolation and user-server backoff (`integrations/tachyon-spring-boot-starter/src/test/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrationTest.java:150`).
- Spring Boot `4.1.1` pinned in module pom only; no Boot BOM import (would override Netty/Jackson/JUnit).

## 📚 Examples (not modules of main reactor)

`examples/`: `weather-mcp`, `weather-mcp-kotlin`, `echo-kotlin`, `mcp-java`, `langchain4j-mcp`, `mcp-skills`, `temporal`. Built by `make examples` (published artifacts) / `make examples-snapshot`.
