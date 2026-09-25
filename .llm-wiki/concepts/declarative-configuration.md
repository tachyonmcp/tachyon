---
title: Declarative configuration
tags: [concept, configuration, annotations]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/JavaTypeSchemas.java]
updated: 2026-09-25
commit: 55b278f2
---

# 🏷️ Declarative configuration

Four native method annotations register ordinary MCP handlers ([TachyonAnnotationProvider#register](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)). This page owns their contracts. Stability markers in the same package are not configuration annotations; see [[api-stability]].

## 🔌 Registration

```java
TachyonServer.builder()
        .annotations(a -> a.register(service))
        .build();
```

`AnnotationContext` defaults to the native provider; `withProvider(p)` switches subsequent registrations ([AnnotationContext#currentProvider](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java)). Builder registrations delegate to `server.annotations(...)`, using the server's registries and configured codecs ([DefaultServerBuilder#applyAnnotationRegistrations](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java), [DefaultTachyonServer#annotations](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java)).

Metadata class and invocation receiver can differ. The invoker resolves a callable method once and invokes the proxy, preserving advice ([TachyonAnnotationProvider#register](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [MethodInvoker#invocationMethod](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)). Bean discovery/lifecycle: [[spring-boot]]. External annotation providers: [[integrations]].

## ⚙️ Shared rules

| Rule | Contract | Proof |
|---|---|---|
| Discovery | Class hierarchy plus every reachable interface; synthetic/bridge methods skipped. A superclass or interface declaration is dropped only when a collected method truly overrides it: same name and same erased params after type-variable resolution. Overloads keep both. Private annotated methods rejected. DI candidate selection scans the same hierarchy. | [TachyonAnnotationProvider#declaresFeatures](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [AnnotationInvocationSupport#discoverMethods](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java), [ReflectionUtils#isOverriddenBy](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/ReflectionUtils.java) |
| Generic declarations | A type variable on an interface or superclass declaration reads as what the registered class binds it to, for parameters and return type; an unbound variable stays a variable. LangChain4j reads the declaring `Method` alone, so it describes bound scalars and rejects other bound types. | [AnnotationInvocationSupport#parameters](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java), [AnnotationInvocationSupport#returnType](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java), [ReflectionUtils#resolverFor](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/ReflectionUtils.java), [MethodInvoker#forTool](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [LangChain4jAnnotationProvider#boundScalarSchema](../../integrations/tachyon-annotations-langchain4j/src/main/java/dev/tachyonmcp/annotations/langchain4j/LangChain4jAnnotationProvider.java) |
| Feature keys | One MCP annotation per method. Duplicate feature keys within one service fail registration; keys are scoped by feature kind. | [TachyonAnnotationProvider#register](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [TachyonAnnotationProvider#claim](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java) |
| Names/descriptions | Tool, resource, and prompt names default to method names when blank. Blank descriptions become absent. | [TachyonAnnotationProvider#nameOf](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [TachyonAnnotationProvider#emptyToNull](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java) |
| Context | `InteractionContext` is injected in any position, excluded from arguments. | [MethodInvoker#namedBindings](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#isContext](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java) |
| Named binding | Explicit `@McpParam(name)` overrides reflection names; otherwise compile with `-parameters`. Descriptions appear in tool properties and prompt arguments. Duplicate names fail registration. Named values are required unless JSpecify `@Nullable` or Optional-family typed. | [MethodInvoker#namedBindings](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [JavaTypeSchemas#isOptional](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/JavaTypeSchemas.java) |
| Scalars | Resource/prompt/named-completion arguments support String, numeric/boolean primitives and wrappers, enums, or Optional-family wrappers of those. Enums bind by exact constant `name()` (same values as tool schema `enum`), independent of JSON SPI; other values ⇒ `must be one of [...]`. | [MethodInvoker#namedBindings](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#coerce](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#enumConstant](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [AnnotationInvocationSupport#JSON_SCHEMA_TYPES](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java), [AnnotationInvocationSupport#requireBindable](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java) |
| Failures | Missing required values and decode failures become `InvalidArgumentException`. Handler exceptions are unwrapped for normal dispatch. | [MethodInvoker#invoke](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#bindNamed](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java) |

Request metadata is opt-in through `@Meta`: raw `Map<String, Object>` gets empty map when absent; record/POJO metadata uses payload decoding and requires presence unless nullable. Metadata never enters schemas or prompt arguments; one metadata parameter per method. Conflicting annotations and unsupported metadata types fail registration ([MethodInvoker#bindMeta](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#validateParameters](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)). Absence means the whole metadata object, not missing application fields; protocol envelope-only objects are still decoded. All native handlers pass request metadata, including both completion shapes ([TachyonAnnotationProvider#registerTool](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [MethodInvoker#invokeCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)).

## 🛠️ Tools — @McpTool

Options: `name`, `description` ([McpTool](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpTool.java)).

```java
@McpTool(description = "Add two integers")
int add(int left, int right) {
    return left + right;
}
```

After excluding context and `@Meta`, a single record/POJO/Map parameter receives the whole arguments object unless `@McpParam` forces named binding. Otherwise arguments bind by parameter name, including decoded object-valued arguments ([MethodInvoker#forTool](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)).

Input schema follows the binding shape; a whole-arguments `Map<String, V>` keeps its `additionalProperties` value schema (generic type, not erased `Map.class`). An object-classified declared return type supplies an output schema through `JsonSchema.generate`; schema internals belong in [[json-layer]] ([MethodInvoker#inputSchema](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [TachyonAnnotationProvider#registerTool](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

## 📄 Resources — @McpResource

Options: required `uri`, plus `name`, `description`, `mimeType` ([McpResource](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpResource.java)).

```java
@McpResource(uri = "config://mode", mimeType = "text/plain")
String mode() {
    return "production";
}

@McpResource(uri = "users://{id}")
String user(String id) {
    return "User " + id;
}
```

No URI variables ⇒ static resource, no named arguments. URI variables ⇒ resource template; the template variable set must exactly equal the non-context parameter-name set; missing or extra names fail registration. Blank URI fails registration ([TachyonAnnotationProvider#registerResource](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

For mapped contents, nonblank `mimeType` wins. Otherwise an object-classified declared return type selects `application/json`; other types leave MIME unset. Explicit `ResourceContents` retains its own fields ([TachyonAnnotationProvider#mimeTypeOf](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [ResultMappers#resourceContents](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java)).

On the 2026-07-28 wire, resources whose method declares `@Meta` use private response caching; metadata-free annotated resources remain public ([TachyonAnnotationProvider#privateCachingIfNeeded](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [McpResponseMapper#readResourceResult](../../tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/codecs/McpResponseMapper.java)).

## 💬 Prompts — @McpPrompt

Options: `name`, `description`, `role` (default `Role.USER`) ([McpPrompt](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpPrompt.java)).

```java
@McpPrompt(name = "trip", description = "Plan a trip")
String trip(String city, @Nullable String country) {
    return "Plan a trip to " + city + (country == null ? "" : ", " + country);
}
```

Each named scalar becomes an advertised prompt argument with its required flag. Values bind from the prompt request ([MethodInvoker#promptArguments](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [TachyonAnnotationProvider#registerPrompt](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)). The annotation role applies only to generated messages; explicit messages/results keep their own roles ([ResultMappers#promptResult](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java)).

## ✨ Completions — @McpCompletion

Options: exactly one nonblank `prompt` name or `resource` URI/template, matched verbatim. One completion method per target per service; prompt and resource targets are distinct ([McpCompletion](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpCompletion.java), [TachyonAnnotationProvider#registerCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

```java
@McpCompletion(prompt = "trip")
List<String> completeCity(String city, @Nullable String country) {
    return searchCities(city, country);
}
```

First non-injected parameter must be String. Its explicit `@McpParam` name or reflection name selects the completed argument; its value is current partial text, including empty text. Later scalars bind resolved siblings using the shared required/nullable rules. Partial text overrides stale context for the same argument ([MethodInvoker#forCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#invokeCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)).

Different argument ⇒ empty result without invoking the named handler ([TachyonAnnotationProvider#registerCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

For several arguments on one target, take one `CompletionRequest`, optionally with context. It exposes argument identity, partial text, siblings, and metadata; a separate `@Meta` injection is also supported. Cannot mix full request and named arguments ([McpCompletion](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpCompletion.java), [MethodInvoker#forCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)). Registration uses existing completion registries; no schema generation ([TachyonAnnotationProvider#registerCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

Registration order per service: all tools/resources/prompts first, then completions. A named completion argument must exist on its target when the target is known — declared by the same service, or already in the prompt/resource-template registry. Unknown ⇒ `@McpCompletion argument 'x' is not declared by prompt 'p' [..]` at build. Target not yet registered (another service later) ⇒ unchecked ([TachyonAnnotationProvider#register](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [TachyonAnnotationProvider#registeredArguments](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

### 🔤 Enum auto-completion

Prompt or resource-template enum arguments (incl. `Optional<Enum>`) get a completion handler for free: case-insensitive prefix over constant `name()`s; other arguments ⇒ empty. Skipped when the same service declares `@McpCompletion` for that target, or when the provider has enum completions off ([TachyonAnnotationProvider#registerEnumCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [MethodInvoker#enumArguments](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)).

Derived, never overriding: the fallback is registered only through the internal if-absent API, so an explicit `@McpCompletion` from **any** service wins whichever order the services register in — registered earlier it survives, registered later it replaces the fallback ([TachyonAnnotationProvider#registerEnumCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [CompletionRegistry#registerForPromptIfAbsent](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/completions/CompletionRegistry.java), [DefaultCompletionRegistry#registerForPromptIfAbsent](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/completions/DefaultCompletionRegistry.java)). Public `Completions` has no if-absent method — derived handlers need `CompletionRegistry`, so a context whose `completions()` is a caller's own implementation fails fast instead of clobbering ([TachyonAnnotationProvider#requireCompletionRegistry](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)). Optional per provider: [TachyonAnnotationProvider#withEnumCompletions](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java) (`false` ⇒ no derived handlers at all; `true` ⇒ `instance()`). See [[feature-registries]].

## 🎁 Result mapping

| Annotation | Accepted results and mapping | Null behavior | Proof |
|---|---|---|---|
| `@McpTool` | `ToolResult` passes through; `ContentBlock` becomes content; scalars/enums become text; Iterable/array becomes JSON text; other objects become structured content. | `void`/null ⇒ empty content | [ResultMappers#toolResult](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java) |
| `@McpResource` | `ResourceContents` passes through; String ⇒ text, byte[] ⇒ blob, other objects ⇒ JSON text at the requested URI. | Invocation error | [ResultMappers#resourceContents](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java) |
| `@McpPrompt` | `PromptResult` passes through; String/ContentBlock/object ⇒ one message (object as JSON text); `PromptMessage` preserves its fields; List maps each element to a message. | Null result or list element ⇒ invocation error | [ResultMappers#promptResult](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java) |
| `@McpCompletion` | `CompletionResult` passes through with total/hasMore/metadata; `List<String>` ⇒ candidates. Other declared return types fail registration. | Null result or null/non-string list element ⇒ invocation error | [ResultMappers#requireCompletionReturnType](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java) |

## 🧭 Related pages

- [[configuration]] — builder groups and defaults.
- [[feature-registries]] — runtime registries and capability resolution.
- [[testing]] — declarative wire coverage and registration-validation tests.
