---
title: integrations
tags: [module, integrations]
sources: [integrations/pom.xml, integrations/tachyon-annotations-mcp-java/, integrations/tachyon-annotations-langchain4j/, integrations/tachyon-annotations-spring-ai/, integrations/tachyon-opentelemetry/, integrations/tachyon-tasks-temporal/]
updated: 2026-09-15
commit: 751331f4
---

# 🔌 integrations

External library adapters: MCP Java SDK, LangChain4j, Spring AI, OpenTelemetry, and Temporal. Spring Boot has its own page: [[spring-boot]].

| Module | SPI implemented | Entry | Proof |
|---|---|---|---|
| `tachyon-annotations-mcp-java` | `AnnotationProvider` | `McpJavaAnnotationProvider.instance()` — scans official MCP Java SDK annotations (`@Tool`, `@Resource`, …) | `McpJavaAnnotationProvider` |
| `tachyon-annotations-langchain4j` | `AnnotationProvider` | `new LangChain4jAnnotationProvider()` — LangChain4j `@Tool` | `LangChain4jAnnotationProvider` |
| `tachyon-annotations-spring-ai` | `AnnotationProvider` | `SpringAiAnnotationProvider.instance()` — Spring AI `@McpTool/@McpResource/@McpPrompt`; `@McpResource` needs non-blank uri | `SpringAiAnnotationProvider` |
| `tachyon-opentelemetry` | `ObservationListener` | `McpOpenTelemetryListener.create(otel)` | [[observability]] |
| `tachyon-tasks-temporal` | `TaskConnector` | `TemporalTaskExecutionEngine.builder(client).taskQueue(..).route(..).build().connector()` | [[tasks]] |

Native Tachyon annotation configuration: [[declarative-configuration]]. Boot bean discovery and lifecycle: [[spring-boot]].

## 📚 Examples (not modules of main reactor)

`examples/`: `weather-mcp`, `weather-mcp-kotlin`, `weather-mcp-spring-boot`, `echo-kotlin`, `mcp-java`, `langchain4j-mcp`, `mcp-skills`, `temporal`. Built by `make examples` (published artifacts) / `make examples-snapshot`.
