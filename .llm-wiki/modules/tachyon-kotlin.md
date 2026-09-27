---
title: tachyon-kotlin
tags: [module, kotlin, dsl]
sources: [tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/, tachyon-kotlin/src/main/resources/META-INF/services/, tachyon-kotlin-kt-schema/src/main/kotlin/dev/tachyonmcp/kotlin/server/json/ktschema/]
updated: 2026-09-27
commit: 5a85c0fc
---

# 🟣 tachyon-kotlin (+ kt-schema)

Verdict: thin adapter. DSL builder wraps Java `ServerBuilder`; `suspend` handlers run as coroutines on the **server's VT executor** via an internal `ServerExtension`; kotlinx-serialization JSON plugs into the same api SPIs. Java first, Kotlin adapts (AGENTS.md).

## 🚪 Entry points

`tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/TachyonServerFactory.kt`:
- `TachyonServer(port) { … }` — build **and start** `TachyonServerFactory#TachyonServer`.
- `buildServer { … }` — build only `TachyonServerFactory#buildServer`.

## 🏗️ DSL

- `TachyonServerBuilder` receiver `TachyonServerBuilder`; `tool(...)` overloads `TachyonServerBuilder`, `TachyonServerBuilder#tool`, `resource` `TachyonServerBuilder#resource`, `prompt` `TachyonServerBuilder#prompt`, `resourceTemplate` `TachyonServerBuilder#resourceTemplate`, `promptCompletion` `TachyonServerBuilder#promptCompletion`, `resourceCompletion` `TachyonServerBuilder#resourceCompletion`, [TachyonServerBuilder#name](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TachyonServerBuilder.kt), `extensions`, [TachyonServerBuilder#pipelineCustomizer](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TachyonServerBuilder.kt).
- One scope per file `config/*Scope.kt`: `CapabilitiesScope`, `NetworkScope`, `SessionScope`, `RuntimeScope`, `ServerInfoScope`, `JsonScope`, `ObservabilityScope`, `PayloadCaptureScope`, `TasksScope`, `ToolScope`, `ResourceScope`, `ResourcesScope`, `PromptScope`, `TemplateScope`, `CompletionScope`, `ContentScope`, `FeatureScope`. `@TachyonDsl` marker `TachyonDsl.kt`.
- `SessionScope`: options are the opt-in — `SessionScope#enable` turns sessions on with defaults, `TachyonServerBuilder#stateless` is the opt-out, `SessionScope#enabled` is deprecated. `SessionScope#sessionIdGenerator` exists as a nullable property **and** a lambda overload whose request is non-null (`McpDispatcher` substitutes an empty request).
- `ToolScope#tasks` = `TasksExtension.tasks(ctx)`. Member, not extension: inside `tool { }` the outer `TachyonServerBuilder#tasks(connector)` member beats an imported `ToolScope` extension. Server side: `TachyonServer.tasks` extension property `TasksExtensions.kt` (`@file:JvmSynthetic`: Java uses `TasksExtension.tasks(server)`). `ToolScope#tasks` resolves once per call (`lazy(NONE)`). `tachyon-extensions-tasks` is `<optional>` in [tachyon-kotlin/pom.xml](../../tachyon-kotlin/pom.xml): all three touch `TasksExtension` lazily, so a server without the jar runs until first use. `requireTasksModule` (`TasksExtensions.kt`) wraps them: a `NoClassDefFoundError` from `dev/tachyonmcp/extensions/tasks/` ⇒ ISE naming the dependency (cause kept); any other missing class rethrown as is.
- `ToolScope` result helpers: `success`, `text`, `raw`, `empty`, `fail(msg|{content})`, `inputRequired`, `content { }` `ToolScope`.
- Registration funnels through `KotlinFeatureRegistrar` `KotlinFeatureRegistrar`.
- Post-build registration on server: `registerTool/Resource/ResourceTemplate/Prompt/PromptCompletion/ResourceCompletion` extensions + reified typed `registerTool<In, Out>` `TachyonServer`, `TachyonServer`; impl `DefaultKotlinTachyonServer` `DefaultKotlinTachyonServer`.
- Domain factories `domain/*Factories.kt` (content, icons, annotations, resources, requests, structured builders, task snapshots). `TaskSnapshot(from) { }` `TaskSnapshotFactories.kt`: `TaskSnapshotBuilder` seeds from `from` and builds via `TaskSnapshot.Builder#next(from)` (revision + 1 unless set), exposes `kotlin.time.Instant`/`Duration` (internal `@OptIn(ExperimentalTime::class)`, callers need none), `requireNotNull` on required fields, delegates to Java `TaskSnapshot.builder()` for validation. Not a `Tasks.publish { }` extension: the Java `publish(Consumer)` member wins via SAM conversion, so a same-named receiver-lambda extension never resolves.
- 🪶 No `inline` in the DSL except `reified` generics — `typedTool<In, Out>` `TachyonServerBuilder#typedTool`, `registerTool<In, Out>` `TachyonServer#registerTool`, `Args#decode`, `JsonArray#valuesAs`, `ToolRequest#arguments`. Scope entries and type-named factories are plain `fun` with an `EXACTLY_ONCE` `callsInPlace` contract, so their builders' ctor/`build`/`applyTo`/`toConfig` stay plain `internal` (mangled `$tachyon_kotlin`, not published). One `@PublishedApi` survives: `toolDescriptorOf` `ToolDescriptorScope`, reached from the two reified registrars. See [`docs/architecture/guidance.md`](../../docs/architecture/guidance.md) *Kotlin adapter shape*.

## 🌀 Coroutines

`CoroutineRuntime` internal `ServerExtension` id `dev.tachyonmcp/kotlin-coroutines`, `AdvertiseMode.NEVER` `CoroutineRuntime`:
- `bootstrap`: `CoroutineScope(SupervisorJob + executor.asCoroutineDispatcher() + CoroutineName("tachyon-kotlin"))`.
- `future(name, block)`: launch → `CompletableFuture`; future cancel ⇒ job cancel; job fail ⇒ future fail `CoroutineRuntime#future`.
- `shutdown`: cancel scope, wait up to `shutdownGracePeriod` `CoroutineRuntime#shutdown`.
- `*FnFactory.kt` adapt suspend lambdas to Java async SAMs.

## 🧾 JSON

- `KxSerializationSerde(Json)` implements `PayloadSerde` `KxSerializationSerde`.
- `KotlinxJsonElementFactory` / `KotlinxJsonObjectFactory` (`JsonDocumentFactory` + `JsonSchemaFactory`) via ServiceLoader `tachyon-kotlin/src/main/resources/META-INF/services/`.
- `JsonInterop.kt`: `JsonElement.toJacksonNode()`, `JsonObject.toJsonSchema()`, `ToolDescriptor.Builder.schemas(...)`.
- kt-schema module: `KtSchemaReflectionFactory : JsonSchemaFactory<Class<*>>` (reflection-generated schema) + `ktSchemaGenerator(...)` helper `KtSchemaReflectionFactory#sourceType`, `helpers#ktSchemaGenerator`. Generator failure (e.g. Java record with `int`) ⇒ `Optional.empty()`, chain falls to core `JavaTypeSchemaFactory` (`KtSchemaReflectionFactory#toJsonSchema`). Core's `KtSchemaResourceFactory` reads build-time generated schemas instead → [[json-layer]].

## 🧪 Tests

`mvn test -pl tachyon-kotlin -am`. Kotest + JUnit: `StatefulServerTest`, `StatelessServerTest`, `KotlinApiTest`, `*DescriptorAttributesTest`, `ToolFnFactoryTest`, `JsonInteropTest`, `KxSerializationTest`, `McpProbe.kt` helper. Kotlin e2e in `e2e/src/test/kotlin/dev/tachyonmcp/e2e/` → [[testing]].
