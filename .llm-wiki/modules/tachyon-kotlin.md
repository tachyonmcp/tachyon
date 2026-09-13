---
title: tachyon-kotlin
tags: [module, kotlin, dsl]
sources: [tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/, tachyon-kotlin/src/main/resources/META-INF/services/, tachyon-kotlin-kt-schema/src/main/kotlin/dev/tachyonmcp/kotlin/server/json/ktschema/]
updated: 2026-09-13
commit: 582f9c52
---

# 🟣 tachyon-kotlin (+ kt-schema)

Verdict: thin adapter. DSL builder wraps Java `ServerBuilder`; `suspend` handlers run as coroutines on the **server's VT executor** via an internal `ServerExtension`; kotlinx-serialization JSON plugs into the same api SPIs. Java first, Kotlin adapts (AGENTS.md).

## 🚪 Entry points

`tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/TachyonServerFactory.kt`:
- `TachyonServer(port) { … }` — build **and start** `:23-33`.
- `buildServer { … }` — build only `:43-50`.

## 🏗️ DSL

- `TachyonServerBuilder` receiver `tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TachyonServerBuilder.kt:38`; `tool(...)` overloads `:132-263`, `:473`, `resource` `:285/:322`, `prompt` `:342/:375`, `resourceTemplate` `:396/:431`, `promptCompletion` `:444`, `resourceCompletion` `:460`, `name`, `extensions`, `pipelineCustomizer` `:498-515`.
- One scope per file `config/*Scope.kt`: `CapabilitiesScope`, `NetworkScope`, `SessionScope`, `RuntimeScope`, `ServerInfoScope`, `JsonScope`, `ObservabilityScope`, `PayloadCaptureScope`, `MonitoringScope`, `TasksScope`, `ToolScope`, `ResourceScope`, `ResourcesScope`, `PromptScope`, `TemplateScope`, `CompletionScope`, `ContentScope`, `FeatureScope`. `@TachyonDsl` marker `TachyonDsl.kt`.
- `ToolScope` result helpers: `success`, `text`, `raw`, `empty`, `fail(msg|{content})`, `inputRequired`, `content { }` `config/ToolScope.kt:37-136`.
- Registration funnels through `KotlinFeatureRegistrar` `config/KotlinFeatureRegistrar.kt:23-135`.
- Post-build registration on server: `registerTool/Resource/ResourceTemplate/Prompt/PromptCompletion/ResourceCompletion` extensions + reified typed `registerTool<In, Out>` `TachyonServer.kt:53-391`, `:515`; impl `DefaultKotlinTachyonServer` `:397`.
- Domain factories `domain/*Factories.kt` (content, icons, annotations, resources, requests, structured builders).

## 🌀 Coroutines

`CoroutineRuntime` internal `ServerExtension` id `dev.tachyonmcp/kotlin-coroutines`, `AdvertiseMode.NEVER` `features/CoroutineRuntime.kt:21-29`:
- `bootstrap`: `CoroutineScope(SupervisorJob + executor.asCoroutineDispatcher() + CoroutineName("tachyon-kotlin"))`.
- `future(name, block)`: launch → `CompletableFuture`; future cancel ⇒ job cancel; job fail ⇒ future fail `:47-75`.
- `shutdown`: cancel scope, wait up to `shutdownGracePeriod` `:77-91`.
- `*FnFactory.kt` adapt suspend lambdas to Java async SAMs.

## 🧾 JSON

- `KxSerializationSerde(Json)` implements `PayloadSerde` `json/KxSerializationSerde.kt:20`.
- `KotlinxJsonElementFactory` / `KotlinxJsonObjectFactory` (`JsonDocumentFactory` + `JsonSchemaFactory`) via ServiceLoader `tachyon-kotlin/src/main/resources/META-INF/services/`.
- `JsonInterop.kt`: `JsonElement.toJacksonNode()`, `JsonObject.toJsonSchema()`, `ToolDescriptor.Builder.schemas(...)`.
- kt-schema module: `KtSchemaReflectionFactory : JsonSchemaFactory<Class<*>>` (reflection-generated schema) + `ktSchemaGenerator(...)` helper `tachyon-kotlin-kt-schema/src/main/kotlin/dev/tachyonmcp/kotlin/server/json/ktschema/KtSchemaReflectionFactory.kt:21`, `helpers.kt:24`. Core's `KtSchemaResourceFactory` reads build-time generated schemas instead → [[json-layer]].

## 🧪 Tests

`mvn test -pl tachyon-kotlin -am`. Kotest + JUnit: `TachyonServerTest`, `KotlinApiTest`, `*DescriptorAttributesTest`, `ToolFnFactoryTest`, `JsonInteropTest`, `KxSerializationTest`, `McpProbe.kt` helper. Kotlin e2e in `e2e/src/test/kotlin/dev/tachyonmcp/e2e/` → [[testing]].
