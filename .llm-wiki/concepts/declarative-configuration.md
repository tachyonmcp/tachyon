---
title: Declarative configuration
tags: [concept, configuration, annotations]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/JavaTypeSchemas.java]
updated: 2026-09-15
commit: 751331f4
---

# 🏷️ Declarative configuration

Four native method annotations register ordinary MCP handlers ([TachyonAnnotationProvider.java:95](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:95)). This page owns their contracts. Stability markers in the same package are not configuration annotations; see [[api-stability]].

## 🔌 Registration

```java
TachyonServer.builder()
        .annotations(a -> a.register(service))
        .build();
```

`AnnotationContext` defaults to the native provider; `withProvider(p)` switches subsequent registrations ([AnnotationContext.java:37](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/AnnotationContext.java:37)). Builder registrations delegate to `server.annotations(...)`, using the server's registries and configured codecs ([DefaultServerBuilder.java:262](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java:262), [DefaultTachyonServer.java:1044](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java:1044)).

Metadata class and invocation receiver can differ. The invoker resolves a callable method once and invokes the proxy, preserving advice ([TachyonAnnotationProvider.java:95](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:95), [MethodInvoker.java:53](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:53)). Bean discovery/lifecycle: [[spring-boot]]. External annotation providers: [[integrations]].

## ⚙️ Shared rules

| Rule | Contract | Proof |
|---|---|---|
| Discovery | Class hierarchy and public interfaces; synthetic/bridge methods skipped. Private annotated methods rejected. | [AnnotationInvocationSupport.java:236](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java:236) |
| Feature keys | One MCP annotation per method. Duplicate feature keys within one service fail registration; keys are scoped by feature kind. | [TachyonAnnotationProvider.java:95](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:95), [TachyonAnnotationProvider.java:229](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:229) |
| Names/descriptions | Tool, resource, and prompt names default to method names when blank. Blank descriptions become absent. | [TachyonAnnotationProvider.java:233](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:233), [TachyonAnnotationProvider.java:253](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:253) |
| Context | `InteractionContext` is injected in any position, excluded from arguments. | [MethodInvoker.java:186](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:186), [MethodInvoker.java:203](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:203) |
| Named binding | Compile with `-parameters`; missing names fail registration. Named values are required unless JSpecify `@Nullable` or Optional-family typed. | [MethodInvoker.java:186](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:186), [JavaTypeSchemas.java:124](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/JavaTypeSchemas.java:124) |
| Scalars | Resource/prompt/named-completion arguments support String, numeric/boolean primitives and wrappers, or Optional-family wrappers of those. | [MethodInvoker.java:79](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:79), [AnnotationInvocationSupport.java:52](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java:52), [AnnotationInvocationSupport.java:96](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/annotations/AnnotationInvocationSupport.java:96) |
| Failures | Missing required values and decode failures become `InvalidArgumentException`. Handler exceptions are unwrapped for normal dispatch. | [MethodInvoker.java:163](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:163), [MethodInvoker.java:170](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:170) |

## 🛠️ Tools — @McpTool

Options: `name`, `description` ([McpTool.java:46](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpTool.java:46)).

```java
@McpTool(description = "Add two integers")
int add(int left, int right) {
    return left + right;
}
```

After excluding context, a single record/POJO/Map parameter receives the whole arguments object. Otherwise arguments bind by parameter name, including decoded object-valued arguments ([MethodInvoker.java:66](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:66)).

Input schema follows the binding shape. An object-classified declared return type supplies an output schema through `JsonSchema.generate`; schema internals belong in [[json-layer]] ([MethodInvoker.java:108](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:108), [TachyonAnnotationProvider.java:127](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:127)).

## 📄 Resources — @McpResource

Options: required `uri`, plus `name`, `description`, `mimeType` ([McpResource.java:33](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpResource.java:33)).

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

No URI variables ⇒ static resource, no named arguments. URI variables ⇒ resource template; each named parameter must occur in the template, but unused template variables are allowed. Blank URI fails registration ([TachyonAnnotationProvider.java:135](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:135)).

For mapped contents, nonblank `mimeType` wins. Otherwise an object-classified declared return type selects `application/json`; other types leave MIME unset. Explicit `ResourceContents` retains its own fields ([TachyonAnnotationProvider.java:237](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:237), [ResultMappers.java:65](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java:65)).

## 💬 Prompts — @McpPrompt

Options: `name`, `description`, `role` (default `Role.USER`) ([McpPrompt.java:33](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpPrompt.java:33)).

```java
@McpPrompt(name = "trip", description = "Plan a trip")
String trip(String city, @Nullable String country) {
    return "Plan a trip to " + city + (country == null ? "" : ", " + country);
}
```

Each named scalar becomes an advertised prompt argument with its required flag. Values bind from the prompt request ([MethodInvoker.java:124](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:124), [TachyonAnnotationProvider.java:184](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:184)). The annotation role applies only to generated messages; explicit messages/results keep their own roles ([ResultMappers.java:76](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java:76)).

## ✨ Completions — @McpCompletion

Options: exactly one nonblank `prompt` name or `resource` URI/template, matched verbatim. One completion method per target per service; prompt and resource targets are distinct ([McpCompletion.java:41](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpCompletion.java:41), [TachyonAnnotationProvider.java:205](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:205)).

```java
@McpCompletion(prompt = "trip")
List<String> completeCity(String city, @Nullable String country) {
    return searchCities(city, country);
}
```

First non-context parameter must be String. Its reflection name selects the completed argument; its value is current partial text, including empty text. Later scalars bind resolved siblings using the shared required/nullable rules. Partial text overrides stale context for the same argument ([MethodInvoker.java:84](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:84), [MethodInvoker.java:101](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:101)).

Different argument ⇒ empty result without invoking the named handler ([TachyonAnnotationProvider.java:222](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:222)).

For several arguments on one target, take one `CompletionRequest`, optionally with context. It exposes argument identity, partial text, siblings, and metadata. Cannot mix full request and named arguments ([McpCompletion.java:26](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpCompletion.java:26), [MethodInvoker.java:84](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java:84)). Registration uses existing completion registries; no schema generation ([TachyonAnnotationProvider.java:205](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProvider.java:205)).

## 🎁 Result mapping

| Annotation | Accepted results and mapping | Null behavior | Proof |
|---|---|---|---|
| `@McpTool` | `ToolResult` passes through; `ContentBlock` becomes content; scalars/enums become text; Iterable/array becomes JSON text; other objects become structured content. | `void`/null ⇒ empty content | [ResultMappers.java:51](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java:51) |
| `@McpResource` | `ResourceContents` passes through; String ⇒ text, byte[] ⇒ blob, other objects ⇒ JSON text at the requested URI. | Invocation error | [ResultMappers.java:65](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java:65) |
| `@McpPrompt` | `PromptResult` passes through; String/ContentBlock/object ⇒ one message (object as JSON text); `PromptMessage` preserves its fields; List maps each element to a message. | Null result or list element ⇒ invocation error | [ResultMappers.java:76](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java:76) |
| `@McpCompletion` | `CompletionResult` passes through with total/hasMore/metadata; `List<String>` ⇒ candidates. Other declared return types fail registration. | Null result or null/non-string list element ⇒ invocation error | [ResultMappers.java:28](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/ResultMappers.java:28) |

## 🧭 Related pages

- [[configuration]] — builder groups and defaults.
- [[feature-registries]] — runtime registries and capability resolution.
- [[testing]] — declarative wire coverage and registration-validation tests.
