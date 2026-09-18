---
title: "Annotations"
weight: 22
sidebar_order: 22
toc: true
description: |-
  Declare tools, resources, prompts, and completions on plain Java methods — or bridge third-party annotation frameworks (mcp-java, LangChain4j, Spring AI) via the AnnotationProvider SPI.
---

The primary way to declare MCP features is a plain Java class with Tachyon's own annotations from
`dev.tachyonmcp.api.annotations`. Programmatic registration (`server.tools().register(...)`) and
third-party annotation frameworks map onto the same registries.

`@McpTool`, `@McpResource`, `@McpPrompt`, `@McpCompletion`, `ServerBuilder.annotations(...)`, `AnnotationContext`,
`AnnotationProvider`, and `AnnotationRegistrationContext` are `@ExperimentalApi` — the shape may
still change.

## Declarative services

Native annotations are included with `tachyon-core`. Start with the [Quickstart](quickstart.md), then
follow the annotation examples for [tools](features/tools.md), [resources](features/resources.md),
[prompts](features/prompts.md), and [completions](features/completions.md).

Compile with `-parameters` to preserve named arguments. For Maven, set
`<maven.compiler.parameters>true</maven.compiler.parameters>` in your project's `<properties>`.
For Gradle Kotlin DSL:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
```


```java
class WeatherService {
    record ForecastRequest(String city, int days) {}
    record Forecast(String city, int days, double celsius) {}

    @McpTool(description = "Forecast for a city")
    Forecast forecast(ForecastRequest request) { ... }

    @McpTool
    String greet(String name, @Nullable String title, InteractionContext ctx) { ... }

    @McpResource(uri = "weather://cities/{city}")
    String city(String city) { ... }

    @McpPrompt
    String trip(String city, Optional<String> season) { ... }

    @McpPrompt(role = Role.ASSISTANT)
    String opener(String city) { ... }
}

var server = TachyonServer.builder()
    .annotations(a -> a.register(new WeatherService()))
    .build();
```

Only the annotation is required. `name` defaults to the method name; `description` is optional;
`@McpResource` needs `uri`; `@McpPrompt` `role` defaults to `Role.USER`.

| Rule | Behaviour |
|---|---|
| Discovery | non-private methods on the class, superclasses, public interfaces (class proxies work) |
| `InteractionContext` parameter | injected, never advertised |
| `@McpTool` with one record/POJO/`Map` argument and no `@McpParam` | whole `arguments` object decoded into it; its schema is `inputSchema` |
| Other parameters | one named argument each (explicit `@McpParam(name)` or compile with `-parameters`); required unless JSpecify `@Nullable` (preferred) or `Optional*` |
| Missing required named argument | invalid-params error |
| `@McpTool` result | `void`/`null` → empty; `ToolResult` passes; `String`/number/boolean/enum → text; `ContentBlock` → content; collection/array → JSON text; other object → `structuredContent` and its type becomes `outputSchema` |
| `@McpResource` | `{var}` in `uri` → template, variables must exactly match non-context parameter names and bind to same-named scalar parameters; static resources take no arguments. Result: `ResourceContents` passes, `String` → text, `byte[]` → blob, object → JSON text (`application/json` default) |
| `@McpPrompt` | scalar parameters become prompt arguments. Result: `PromptResult` passes; `String`, `ContentBlock`, or object (as JSON text) → one message with the annotation's `role`; `PromptMessage` passes with its own role; `List` of those → messages |
| `@McpCompletion` | one prompt/resource target; `CompletionRequest` or named partial-value/sibling parameters; returns `CompletionResult` or `List<String>` (see below) |
| Exceptions | checked exceptions propagate as from the corresponding synchronous feature function (`ToolFn`, `CompletionFn`, etc.) |
| Fail fast at registration | two feature annotations on one method, duplicate tool/prompt name or resource URI, private method, static resource with arguments, missing or extra resource-template parameter, non-scalar prompt/resource parameter; completion target/signature/return-type violations or duplicate completion targets |

### Explicit parameter names and request metadata

Use `@McpParam(name = "query", description = "Search text")` to override a Java
parameter name and describe its tool schema property or prompt argument.
An explicit nonblank name works without `-parameters`; an omitted name still
requires that compiler flag. Names also select resource-template variables and
completion arguments. Duplicate argument names fail registration.

Single-object tool binding remains available: after excluding injected context and
metadata, one record, POJO, or map receives the whole arguments object.
Adding `@McpParam`, even with only a description, switches that tool to named binding.

```java
record TenantMeta(String tenant) {}

@McpTool
String search(
        @McpParam(name = "query", description = "Search text") String text,
        @Meta TenantMeta metadata) {
    return metadata.tenant() + ":" + text;
}
```

`@Meta` injects request `_meta` into tools, resources, prompts, and completions,
including methods accepting `CompletionRequest`. It never contributes to argument
schemas or prompt arguments. Declare either `Map<String, Object>` for raw entries
(empty when absent), or a record/POJO decoded by the configured payload deserializer.
Missing typed metadata becomes null for `@Nullable` parameters; otherwise it rejects
the request. Decode failures are invalid-argument errors. An empty metadata object
is decoded normally. In protocol 2026-07-28, required protocol envelope fields
mean metadata is present even when no application metadata was sent; missing record
fields then follow the configured deserializer's behavior.

Only one `@Meta` parameter is allowed. Scalar metadata, other map declarations, and
combining `@Meta` with `@McpParam` fail registration. Neither annotation may decorate
injected `InteractionContext` or `CompletionRequest` parameters.

Schemas come from `JsonSchema.generate(type)`: a build-time kt-schema resource or the kt-schema
reflection generator when present, otherwise tachyon-core's `JavaTypeSchemaFactory` (records,
POJOs via public getters/fields, enums, collections, maps, `Optional`, `java.time`, `UUID`, `URI`).

In Spring Boot, the [`tachyon-spring-boot-starter`](https://github.com/tachyonmcp/tachyon/tree/main/integrations/tachyon-spring-boot-starter)
registers every such bean automatically.

## Argument completions

Use `@McpCompletion(prompt = "trip")` for a prompt or
`@McpCompletion(resource = "weather://{city}")` for an exact resource URI/template reference.
Specify exactly one target. The target can be declared elsewhere; a completion-only Spring bean
is discovered too.

The simple signature uses explicit `@McpParam(name)` values or reflection parameter names
(`-parameters` required for reflection names):

```java
@McpCompletion(prompt = "trip")
List<String> completeCity(String city, @Nullable String country) {
    return cities.findStartingWith(city, country);
}
```

After excluding `InteractionContext` and `@Meta`, the first parameter must be `String`.
Its effective name (`city`) selects the
argument being completed and receives the partial text, including an empty string. Later named
scalar parameters receive resolved sibling arguments from the request context, using the configured
payload codecs for conversion. Missing siblings need `@Nullable` (or a supported `Optional` type)
unless they are required. The partial value wins if context also contains a stale value for `city`.
Requests completing a different argument return no candidates without invoking the method.

For a target that completes several arguments, take `CompletionRequest` instead:

```java
@McpCompletion(prompt = "trip")
CompletionResult completeTrip(CompletionRequest request, InteractionContext context) {
    return suggestions.complete(request.argumentName(), request.argumentValue(), request.resolvedArguments());
}
```

These are alternative signatures for one target. Both allow an injected `InteractionContext` in any
position and one `@Meta` parameter. Do not mix `CompletionRequest` with named arguments. A service may declare only one
completion method for each prompt or resource target; duplicates fail registration.

Return `List<String>` for candidates only, or `CompletionResult` to retain `total`, `hasMore`, and
`_meta`. Null results and invalid candidates fail the request. Existing completion dispatch applies
the 100-candidate limit. Calls run on virtual threads; checked exceptions follow `CompletionFn` error
mapping. Spring proxy advice remains active; parameter annotations and reflection names come
from the annotated target method. Completion annotations do not generate JSON schemas.

## Registration and dependency injection

An already-built Java server also supports `server.annotations(a -> a.register(service))`.
This uses the server's configured payload serializers and feature registries. Registration runs
immediately, in order; a group of registrations is not transactional. DI containers can construct
the server, inject it into service beans, register those beans, and then call `server.start()`.

The [Spring Boot starter](spring-boot.md) follows that order. It discovers singleton beans after initialization,
reads annotation metadata from Spring AOP target classes, and invokes JDK/class proxies so advice
still runs. For JDK proxies, each annotated method must be exposed by an interface. A user-provided
server keeps its own registration policy.

Container integrations can call `TachyonAnnotationProvider.register(proxy, targetClass, context)`
to separate annotation metadata from the invocation receiver. A method absent from the proxy fails
registration; the provider never bypasses advice by invoking the target directly.

## The AnnotationProvider interface

Each implementation knows how to inspect one particular annotation framework and translate its
annotated methods into standard Tachyon feature registrations via an `AnnotationRegistrationContext`
— the same `Tools`/`Resources`/`Prompts`/`Completions` façades a manual registration or a
`ServerExtension` would use. Tachyon core never references framework-specific annotations; each
provider lives in its own optional integration module.

```java
public interface AnnotationProvider {
    void register(Object instance, AnnotationRegistrationContext context);
}
```

Implementations are stateless and reusable — the same provider instance may be passed to multiple
objects.

## Register third-party annotated objects

```java
var server = TachyonServer.builder()
    .annotations(a -> a
        .withProvider(new McpJavaAnnotationProvider())
        .register(new WeatherService())
        .register(new CalculatorService()))
    .build();
```

- Each call to `withProvider(...)` sets the active provider; subsequent `register(...)` calls dispatch
  through that provider until a new one is set. Multiple providers and multiple objects are
  supported in one chain.
- Calling `.annotations(...)` more than once composes — every configurer runs against the same
  registration context, in call order.
- Annotation registrations run last: after the server is constructed, and after the
  `withTools`/`withResources`/`withPrompts`/`withCompletions` bootstrap registrations. An
  annotated method sharing a name with a bootstrap registration therefore replaces it.
- Before any `withProvider(...)`, `register(...)` uses `TachyonAnnotationProvider` (`@McpTool`
  and friends).
- `TachyonAnnotationProvider.withEnumCompletions(false)` is the same provider with automatic enum
  completion off; `withEnumCompletions(true)` is `instance()`. See
  [completions](features/completions.md#complete-enum-values-automatically).

## Third-party providers

| Module | Provider | Maps |
|---|---|---|
| `tachyon-annotations-mcp-java` | `McpJavaAnnotationProvider` | `@Tool`, `@Resource`, `@ResourceTemplate`, `@Prompt` |
| `tachyon-annotations-langchain4j` | `LangChain4jAnnotationProvider` | `@Tool` only — LangChain4j has no resource/prompt annotations |
| `tachyon-annotations-spring-ai` | `SpringAiAnnotationProvider` | `@McpTool`, `@McpResource` (static or, when the URI contains `{...}`, a template), `@McpPrompt` |

Add the module you need as a dependency; each is independent of the other two. All three live under
`integrations/` in the source tree. Version is pinned by the `tachyon-bom` — see
[Quickstart](quickstart.md#1-add-the-dependency).

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-annotations-mcp-java</artifactId>
</dependency>
```

All three coerce JSON-RPC arguments to the annotated method's declared parameter type by routing
through the server's configured `PayloadSerializer`/`PayloadDeserializer` (`AnnotationRegistrationContext.payloadSerializer()`/`payloadDeserializer()`), and inject a method parameter of type
`InteractionContext` when the annotated method declares one. A parameter annotated with a
`defaultValue` (e.g. `@P(defaultValue = "10")`, `@ToolArg(defaultValue = "10")`) that's absent from
the incoming arguments is bound to that default, parsed as the parameter's declared type, rather
than `null`.

## Schema generation

`McpJavaAnnotationProvider` describes tool/resource-template/prompt parameters with a scalar-only
mapping (`String`, numeric/boolean primitives and wrappers, and `Optional`/`OptionalInt`/
`OptionalLong`/`OptionalDouble` wrapping one of those) and rejects anything else — a `record`,
`enum`, `List<T>`, or nested POJO parameter — at registration time with a clear exception, rather
than silently misdescribing it as `"string"` or failing only on first invocation.

`LangChain4jAnnotationProvider` and `SpringAiAnnotationProvider` instead delegate tool input-schema
generation to their own framework's schema generator (`dev.langchain4j.agent.tool.ToolSpecifications`
and `org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator`, respectively),
so records, enums, `List<T>`, and nested POJOs are described — and bound — correctly, not rejected.
Both providers still strip parameters neither framework knows about (Tachyon's own
`InteractionContext`) from the generated schema themselves. `SpringAiAnnotationProvider`
additionally rejects, at registration, parameter types under `org.springframework.ai.mcp.annotation`
that aren't one of the three request-context types it actually emulates (`McpSyncRequestContext`,
`McpAsyncRequestContext`, `MetaProvider`) — e.g. `McpMeta` or `McpTransportContext` — instead of
silently resolving them to `null` at invocation.

Return-value conversion mirrors this across all three providers: a `@Tool`/`@McpTool` method
returning anything other than a `ToolResult`, `String`, `ContentBlock`, `List<...>` (in
`McpJavaAnnotationProvider` and `LangChain4jAnnotationProvider`), or scalar (a `Number`, `Boolean`,
or `Character`) is wrapped with `ToolResult.structured(result)`, so a composite return type (a
record or POJO) comes back as real `structuredContent`, not a stringified `toString()` dump. See
the [`langchain4j-mcp`](https://github.com/tachyonmcp/tachyon/tree/main/examples/langchain4j-mcp) example for a full round trip.

### Spring AI prompt arguments

`SpringAiAnnotationProvider` reads `@McpArg`'s `name`, `description`, and `required` for
`@McpPrompt` parameters — a renamed argument (`@McpArg(name = "topicName")`) is looked up under
that name both in the advertised `PromptArgument` and when resolving the incoming
`prompts/get` arguments, so the two stay in agreement.

### Spring AI proxies

`SpringAiAnnotationProvider` scans `instance.getClass().getDeclaredMethods()`. If `instance` is a
Spring-managed bean wrapped in a CGLIB proxy — the default for `@Component`/`@Service` beans using
class-based proxying — `getClass()` returns the proxy class, whose declared methods don't carry the
original annotations. Register the unproxied instance, or a bean with proxying disabled
(`proxyTargetClass = false` with an interface, or `@Scope(proxyMode = ScopeMode.NO)`).

## Implement a provider for another framework

```java
public class MyFrameworkAnnotationProvider implements AnnotationProvider {
    @Override
    public void register(Object instance, AnnotationRegistrationContext context) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            MyTool tool = method.getAnnotation(MyTool.class);
            if (tool == null) continue;
            context.tools().register(
                ToolDescriptor.builder().name(tool.name()).build(),
                (ctx, req) -> ToolResult.text(method.invoke(instance, /* ... */).toString()));
        }
    }
}
```

A well-behaved provider fails fast (throws) on duplicate feature names within the same provider,
invalid annotation combinations, or unsupported method signatures, so callers see the problem at
registration time rather than at first tool call. Tachyon's feature registries don't enforce this
themselves — registering two features under the same name silently replaces the first.

---

## Next steps

- [Tools](features/tools.md) — declare annotated tools and return typed results
- [Extensions](extensions/) — add negotiated protocol behaviour
- [Quickstart](quickstart.md) — run a minimal server
