---
title: Declarative configuration
tags: [concept, configuration, annotations]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/JavaTypeSchemas.java]
updated: 2026-09-15
commit: 9eec1092
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
| Discovery | Class hierarchy and public interfaces; synthetic/bridge methods skipped. Private annotated methods rejected. | [AnnotationInvocationSupport#discoverMethods](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java) |
| Feature keys | One MCP annotation per method. Duplicate feature keys within one service fail registration; keys are scoped by feature kind. | [TachyonAnnotationProvider#register](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [TachyonAnnotationProvider#claim](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java) |
| Names/descriptions | Tool, resource, and prompt names default to method names when blank. Blank descriptions become absent. | [TachyonAnnotationProvider#nameOf](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [TachyonAnnotationProvider#emptyToNull](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java) |
| Context | `InteractionContext` is injected in any position, excluded from arguments. | [MethodInvoker#namedBindings](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#isContext](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java) |
| Named binding | Compile with `-parameters`; missing names fail registration. Named values are required unless JSpecify `@Nullable` or Optional-family typed. | [MethodInvoker#namedBindings](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [JavaTypeSchemas#isOptional](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/JavaTypeSchemas.java) |
| Scalars | Resource/prompt/named-completion arguments support String, numeric/boolean primitives and wrappers, enums, or Optional-family wrappers of those. Enums bind by exact constant `name()` (same values as tool schema `enum`), independent of JSON SPI; other values ⇒ `must be one of [...]`. | [MethodInvoker#namedBindings](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#coerce](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#enumConstant](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [AnnotationInvocationSupport#JSON_SCHEMA_TYPES](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java), [AnnotationInvocationSupport#requireBindable](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java) |
| Failures | Missing required values and decode failures become `InvalidArgumentException`. Handler exceptions are unwrapped for normal dispatch. | [MethodInvoker#invoke](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#bindNamed](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java) |

## 🛠️ Tools — @McpTool

Options: `name`, `description` ([McpTool](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpTool.java)).

```java
@McpTool(description = "Add two integers")
int add(int left, int right) {
    return left + right;
}
```

After excluding context, a single record/POJO/Map parameter receives the whole arguments object. Otherwise arguments bind by parameter name, including decoded object-valued arguments ([MethodInvoker#forTool](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)).

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

First non-context parameter must be String. Its reflection name selects the completed argument; its value is current partial text, including empty text. Later scalars bind resolved siblings using the shared required/nullable rules. Partial text overrides stale context for the same argument ([MethodInvoker#forCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java), [MethodInvoker#invokeCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)).

Different argument ⇒ empty result without invoking the named handler ([TachyonAnnotationProvider#registerCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

For several arguments on one target, take one `CompletionRequest`, optionally with context. It exposes argument identity, partial text, siblings, and metadata. Cannot mix full request and named arguments ([McpCompletion](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpCompletion.java), [MethodInvoker#forCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)). Registration uses existing completion registries; no schema generation ([TachyonAnnotationProvider#registerCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

Registration order per service: all tools/resources/prompts first, then completions. A named completion argument must exist on its target when the target is known — declared by the same service, or already in the prompt/resource-template registry. Unknown ⇒ `@McpCompletion argument 'x' is not declared by prompt 'p' [..]` at build. Target not yet registered (another service later) ⇒ unchecked ([TachyonAnnotationProvider#register](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [TachyonAnnotationProvider#registeredArguments](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java)).

### 🔤 Enum auto-completion

Prompt or resource-template enum arguments (incl. `Optional<Enum>`) get a completion handler for free: case-insensitive prefix over constant `name()`s; other arguments ⇒ empty. Skipped when the same service declares `@McpCompletion` for that target ([TachyonAnnotationProvider#registerEnumCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java), [MethodInvoker#enumArguments](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java)).

⚠️ Registry is last-write-wins per target; an explicit completion from a different service registered *before* the enum prompt gets replaced. See [[findings]].

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
