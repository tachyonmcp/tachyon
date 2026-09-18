---
title: "Handler Design Guidance"
weight: 10
sidebar_order: 10
sidebar_hide: true
build:
  list: never
  render: never
description: |-
  Rules for adding or changing a Tachyon server-feature handler type: protocol boundary mapping, task ownership, SAM design, and API naming conventions.
---

Rules for a new server-feature handler type (tools, resources, prompts, future ones). Read before adding or touching a handler SAM.

## 🎯 Protocol boundary: map at the method handler

Generated MCP models, JSON-RPC payloads, and JSON-library nodes are wire types. They stop at the
protocol method handler. A feature registry is domain code: it receives API request objects and
returns API result objects.

```text
wire JSON -> protocol request mapper -> domain request -> registry/feature handler
registry/domain result -> protocol response mapper -> wire JSON
```

- The protocol method handler owns the RPC method name and calls the request mapper before it calls
  a registry. It maps the returned domain result with the negotiated protocol's response mapper.
- A registry must not implement `RpcMethodHandler`, accept `Object params`, inspect raw `Map`
  payloads, invoke `JsonRpcCodec`/a generated codec, or import generated protocol models.
- A request mapper is version-specific. It decodes its generated model and creates the stable API
  domain request (`ToolRequest`, `PromptRequest`, `ResourceRequest`, and so on). It is the only
  place that understands version-specific fields such as `inputResponses` and `requestState`.
- A response mapper is version-specific and converts only domain results/errors to generated wire
  models. It must not reach into a registry to reconstruct request data.
- Keep JSON-library types inside mappers. Pass API JSON abstractions or ordinary domain values
  across the boundary, so a different JSON backend changes a mapper/configuration, not a registry.

`McpDispatcher` and transport code are wire glue and may handle raw JSON-RPC envelopes. This
exception does not extend to feature registries or user-facing feature handlers.

## 🎯 Tasks are protocol connectors, not job executors

Tachyon owns the MCP task representation. It does not own the work represented by a task.
Applications, workflow engines, job queues, and schedulers start and run that work. Tachyon maps
their state and commands to MCP.

| Concern | Owner |
|---|---|
| Starting and running business work | Application or external execution system |
| Retries, timers, durable waits, recovery | Application or external execution system |
| MCP task IDs and wire-version mapping | Tachyon |
| `tasks/get`, `tasks/list`, `tasks/cancel`, `tasks/update` dispatch | Tachyon |
| Authoritative execution state | Application or external execution system |
| Cached MCP projection and terminal-result retention | Tachyon |
| `notifications/tasks/status` (legacy) / `notifications/tasks` (modern, via `subscriptions/listen`) | Tachyon, from published projections |
| `notifications/progress` for a task | Tachyon, via `Tasks.reportProgress(taskId, ...)` — ephemeral, not part of `TaskSnapshot` |

### 🔴 Forbidden task ownership

Task code must not:

- accept or retain `Runnable`, `Callable`, handler instances, `DispatchContext`, Netty objects, or
  Java closures as task state;
- expose `Future`, `CompletableFuture`, or `CompletionStage` from `Task`, `Tasks`, or any task SPI;
- submit task work to Tachyon's executor;
- retain a map of running futures or interrupt application work for `tasks/cancel`;
- re-invoke a tool handler after `tasks/update`;
- infer external execution state from whether a local future completed;
- transition an external task merely because Tachyon's local TTL janitor ran.

The server-owned virtual-thread executor remains for serving an MCP request. It may block while a
task engine calls an external system. It must never become the lifetime owner of that external
work.

### 🎯 Target task API

`TaskSnapshot` is an immutable, read-only MCP projection. State changes arrive as complete snapshots,
not mutator calls on a task handle. A monotonically increasing `revision` makes callback and refresh
application idempotent: Tachyon ignores a snapshot whose revision is not newer than the cached one.
`@Value.Check` enforces that `status()` agrees with `result()`/`pendingInput()` — e.g. a `COMPLETED`
snapshot without a `TaskResult.Completed`, or a non-terminal snapshot carrying a result, fails at
construction instead of reaching the wire as an invalid union.

The target public model is:

```java
@ExperimentalApi
@Value.Immutable
public interface TaskSnapshot {

    String taskId();
    TaskState status();
    long revision();

    static Builder builder() {
        return DefaultTaskSnapshot.builder();
    }
}

var snapshot = TaskSnapshot.builder()
        .taskId(workflowId)
        .status(TaskState.WORKING)
        .createdAt(observedAt)
        .lastUpdatedAt(observedAt)
        .revision(1)
        .build();
```

The connector is a builder of per-operation SAMs, not an interface to implement — the same
discipline every other feature in this document follows (see "Default shape: two independent SAMs"
and "Own SAM, `throws Exception`" below). Each operation is synchronous and blocking by design;
protocol handlers already run on virtual threads. An implementation may call a database, Temporal,
Camunda, or another MCP server without leaking that client's async type into Tachyon's API.

```java
@ExperimentalApi
public final class TaskConnector {
    public static Builder builder() { ... }

    public interface Builder {
        Builder get(TaskGetFn fn);       // required
        Builder cancel(TaskCancelFn fn); // required; cooperative signal
        Builder update(TaskUpdateFn fn); // required
        TaskConnector build();           // rejects any missing modern operation
    }
}

var tasks = TaskConnector.builder()
        .get((ctx, request) -> workflows.snapshot(request.taskId()))
        .cancel((ctx, request) -> workflows.requestCancellation(request.taskId()))
        .update((ctx, request) -> workflows.submitInput(request.taskId(), request.inputResponses()))
        .build();
```

The modern Tasks extension is atomic: `get`, `cancel`, and `update` are all required. This avoids
partial connector objects and runtime `UnsupportedOperationException` paths. Only legacy operations
remain optional.

Declare the connector together with the Tasks capability. Calling `tasks(...)` enables the feature,
registers the connector, and auto-registers the `io.modelcontextprotocol/tasks` wire extension in one
call; there is no root `ServerBuilder.taskExecutionEngine(...)` setter and no separate
`.withExtensions(TasksExtension.instance())` call:

```java
CapabilitiesConfig.Builder tasks(TaskConnector connector);
```

This keeps capability advertisement, extension registration, and the connector atomic. Building must
reject a task-producing tool without a configured connector. `noTasks()` remains the explicit opt-out.

There is no default or in-process engine. `TaskConnector` is the user/integration SPI. A
task-producing handler starts external work itself and returns its initial snapshot. Tachyon must
not invent a generic `start` operation because external systems require different start contracts.

Legacy `tasks/list` and blocking `tasks/result` support are two optional, deprecated builder methods
on the same connector. Do not force session-scoped operations removed by MCP 2026-07-28 into every
connector:

```java
var tasks = TaskConnector.builder()
        .get((ctx, request) -> workflows.snapshot(request.taskId()))
        .cancel((ctx, request) -> workflows.requestCancellation(request.taskId()))
        .update((ctx, request) -> workflows.submitInput(request.taskId(), request.inputResponses()))
        .list((ctx, request) -> workflows.list(request.limit(), request.cursor()))
        .awaitResult((ctx, request) -> workflows.awaitResult(request.taskId()))
        .build();
```

`awaitResult` may block in the external client's supported wait operation. Tachyon must not
emulate it with a local completion future or a polling loop.

The Tasks facade owns only projection publication and lookup:

```java
public interface Tasks {

    TaskSnapshot publish(TaskSnapshot snapshot);

    @Nullable
    TaskSnapshot get(String taskId);

    boolean remove(String taskId);
}
```

`publish` updates the cache, applies retention policy, and emits any negotiated MCP notification.
It does not start work. `TaskConnector.get()` is authoritative for `tasks/get`; a successful
snapshot is published before mapping the response. A refresh failure must not silently invent a
state. A cached snapshot may be used only under an explicit stale-read policy.

Configure one connector while declaring the capability. The connector is an infrastructure
dependency, not a task executor or task-handler registration:

```java
var server = TachyonServer.builder()
        .capabilities(c -> c.tasks(taskConnector))
        .port(8080)
        .build();
```

Kotlin follows the Java source of truth:

```kotlin
capabilities {
    tasks(taskConnector)
}
```

### 🎯 Task-producing tools

A task-capable tool handler is invoked once. The application starts its external execution and
returns the initial task projection. Tachyon registers that projection and maps it to the negotiated
MCP `CreateTaskResult`. Tachyon never runs the same handler in the background.

The target result shape adds a task branch to `ToolResult`:

```java
var workflowId = workflows.start(request.arguments());
return ToolResult.task(TaskSnapshot.working(workflowId, clock.instant(), 1));
```

For a task-producing call:

1. The tool handler starts or signals the external execution.
2. The handler returns `ToolResult.task(initialSnapshot)`.
3. Tachyon publishes the snapshot and returns the MCP task handle.
4. `tasks/get` calls `TaskConnector.get()` and maps the returned snapshot.
5. `tasks/update` forwards a `TaskUpdateRequest`; it does not aggregate answers or resume a handler.
6. `tasks/cancel` calls `TaskConnector.cancel()` and immediately acknowledges the request.
7. A later `tasks/get` calls `get()` again; cancellation is cooperative and may still be pending or
   settle in another terminal state.

The external task ID should be used directly as the MCP task ID when it is stable and safe to
expose. For Temporal, use the workflow ID rather than a run ID so Continue-As-New preserves the
logical task identity. If identifiers cannot be exposed, the connector owns the durable mapping;
Tachyon must not keep an in-memory execution-reference map.

### 🩶 Push is optional; pull is authoritative

External callbacks may call `Tasks.publish(snapshot)` to reduce notification latency. Push does not
replace reconciliation: callbacks can be duplicated, reordered, delayed, or lost while Tachyon is
offline. `tasks/get` always refreshes through the connector before responding unless an explicitly
configured stale-read policy applies.

Task projection retention is not execution control. The janitor may evict an expired cached terminal
projection. It must not cancel, fail, terminate, or otherwise mutate external work.

## 🎯 Default shape: two independent SAMs

Tools, resources, prompts, and completions use one request shape and two unrelated contracts:

```java
@FunctionalInterface
public interface XFn {
    XResult apply(InteractionContext ctx, XRequest request) throws Exception;
}

@FunctionalInterface
public interface AsyncXFn {
    CompletionStage<? extends XResult> apply(InteractionContext ctx, XRequest request);
}
```

Expose them through `register` and `registerAsync`. Adapt sync functions to the registry's internal
async representation inside the implementation. Never model sync and async as subtypes.

## 🐛 Own SAM, `throws Exception` — never raw `java.util.function.*`

`BiFunction`/`Function` can't declare checked exceptions — reusing them forces every I/O handler into `try/catch` boilerplate and drops the real exception type/stacktrace from the dispatcher's error log. Define a purpose-built SAM, `throws Exception` on the sync method:

```java
@FunctionalInterface
public interface XFn {
    XResult apply(InteractionContext ctx, XRequest request) throws Exception;
}
```

`ToolFn` applies this to tools (it replaced raw `BiFunction`) and receives the full `ToolRequest`. The dispatcher already logs/maps thrown exceptions to a JSON-RPC error — a throwing SAM lets a handler use that path instead of hand-rolling it. `ToolRequest.arguments()` exposes the ergonomic `Args`; the request also carries `_meta` so the shape extends later without an interface change.

**Async entry types don't declare `throws Exception`** — errors propagate via a failed `CompletionStage`, matching `AsyncResourceFn`/`AsyncPromptFn`. Don't add `throws` there "for symmetry."

## 🪶 Sync-first, virtual-thread contract

Blocking for I/O in `handle`/`read` is intended — handlers run on a server-executor virtual thread:

- `HandlerFutures.assumeVirtualThread()` guardrail at the top of every sync dispatch. Don't remove it.
- Never `synchronized`/native calls in a handler (pins the carrier thread) — use `ReentrantLock`.
- `HandlerFutures.joinInterruptibly(stage)` to block on a `CompletionStage` (restores interrupt flag, unwraps `ExecutionException`). Don't hand-roll `.get()`.

## 🏹 When to reach for a heavier dispatch structure

Tool registration uses a descriptor/function pair. `ToolFn` and `AsyncToolFn` both receive the full
`ToolRequest`; read parsed arguments via `request.arguments()`. `ToolHandler` and
`AbstractToolHandler` are experimental class-based escape hatches, not registration types.

## 🪶 Descriptor bundling

Registries take `(descriptor, function)` pairs. Keep descriptor metadata separate from executable
behaviour. Do not add a single-argument `register(Handler)` overload.

## ⚠️ Naming: split sync/async by name, not overload

Use `register`/`registerAsync`; never overload one method name for both sync and async lambda
shapes. Separate names avoid ambiguous Java lambdas.

Feature registration belongs to the runtime façades. `ServerBuilder.withTools`,
`withResources`, `withPrompts`, and `withCompletions` are bootstrap conveniences that delegate to
those same façade APIs; do not add feature-specific registration overloads to `ServerBuilder`.

**Interface/SAM naming:**
- `XFn` — synchronous throwing SAM. It receives the full request.
- `AsyncXFn` — independent asynchronous SAM returning a `CompletionStage`.

## 🪶 Registry/facade API naming

- Registry facade interface named as plural: `interface Completions`, `CompletionRegistry extends Completions`, `DefaultCompletionRegistry implements CompletionRegistry`. User-facing API uses facade.
- Runtime feature registries use `register` / `registerAsync` and `unregister`.
- Optional lookup uses `Optional<Descriptor> find(String name)`. Never nullable `get`.
- Descriptor enumeration uses immutable, name-sorted `descriptors()` snapshots.
- Resource templates follow `registerTemplate`, `registerTemplateAsync`, `unregisterTemplate`, `findTemplate`, `templateDescriptors`.
- Tool registration accepts `(ToolDescriptor, ToolFn)` or `(ToolDescriptor, AsyncToolFn)` through
  `register` and `registerAsync`. Builder-configurer overloads delegate to these methods.
- `TaskRegistry` is excluded. Tasks use runtime lifecycle methods such as `create` and `get`.

## Kotlin adapter shape

- Structured object factories with more than three fields use one canonical type-named receiver builder: `Icon { src = "..."; mimeType = "image/svg+xml" }` (`Annotations` follows this shape despite having three fields — it's nested metadata commonly composed inside other builders). Don't duplicate it with lowercase receiver factories or flat overloads; an owned enclosing DSL may add a singular member such as `argument { }` that delegates to the canonical factory. Required builder fields start nullable and fail with `requireNotNull` in `build()`.
- Type-named receiver factories are plain `public fun`, declare an `EXACTLY_ONCE` contract, and suppress `FunctionName`. Their public builder has a plain `internal` constructor and `build()`.
- `inline` is reserved for `reified` type parameters. A `public inline fun` copies its body into consumer bytecode and forces every member it touches to `@PublishedApi internal` — `public final` in the jar, frozen forever. A configuration DSL saves one `Function1` allocation at construction time; that is never worth the leaked ABI. `callsInPlace` contracts work without `inline`, so definite assignment and smart casts survive.
- Keep DSL operations as receiver-class members when the receiver is owned by this module. Use a top-level extension only for types that cannot own the operation. Type-named factories remain top-level when the Java model has no Kotlin companion.
- Java `ServerBuilder` is the implementation source of truth for server construction and validation. Server feature façades own registration; Kotlin delegates through the builder's `with*` bootstrap conveniences and adds only thin adaptation for suspend lambdas and Kotlin-specific types.
- Do not reimplement Java builder validation, defaulting, or registration collections in Kotlin. Add missing reusable behaviour to Java first, then expose it through the Kotlin DSL.
- Expose one Kotlin server-construction surface: `TachyonServerBuilder`. Do not publish Kotlin extensions on the Java `ServerBuilder`; they bypass Kotlin defaults and duplicate autocomplete. Use an internal owned collaborator when thin adaptation would make the public builder too large.
- Keep Kotlin files focused. Once a file exceeds 300 lines, consider splitting it by owned responsibility. Do not split member DSLs into global extensions merely to reduce line count; prefer composition with an internal class.
- Keep required values first and flexible metadata as defaulted named parameters on the common call. Named arguments remove ambiguity; do not hide useful descriptor fields in a registration sub-DSL. Exception: `extensionId` — see the note below the reference shape.
- Keep a value overload accepting the prebuilt descriptor for reuse, testing, and advanced construction.
- Name a trailing behavioural lambda `block`. Do not add a ceremonial `handler {}` or `read {}` wrapper inside another configuration lambda.
- Use a result DSL when it removes repeated request data. Seed contextual defaults such as the requested resource URI and registered MIME type, while allowing explicit overrides.
- A nested result builder must forward handler context used in expressions inside its block, such as URI-template `param` and `sequence` accessors.

Reference shape:

```kotlin
resourceTemplate(
    name = "user-profile",
    uriTemplate = "user://{userId}/profile",
    description = "User profile template",
    mimeType = "application/json",
    title = "User profile",
    annotations = annotations,
    icons = icons,
    meta = mapOf("owner" to "team-x"),
) {
    TextResourceContents {
        text = """{"id":"${param("userId")}"}"""
    }
}
```

Keep both overload families:

```kotlin
fun resourceTemplate(
    name: String,
    uriTemplate: String,
    description: String? = null,
    mimeType: String? = null,
    title: String? = null,
    annotations: Annotations? = null,
    icons: List<Icon> = emptyList(),
    meta: Map<String, Any>? = null,
    block: suspend TemplateScope.() -> ResourceContents,
)

fun resourceTemplate(
    descriptor: ResourceTemplateDescriptor,
    block: suspend TemplateScope.() -> ResourceContents,
)
```

Every field here maps directly onto the corresponding `*Descriptor.Builder` field — add a new
flat param the moment the Java builder gains one, don't let the Kotlin overload drift behind it.

⚠️ **`extensionId` is the one deliberate exception** — never add it as a flat named parameter on
`tool`/`resource`/`resourceTemplate`/`prompt`/`registerTool`. It marks extension-owned features:
`ResourceMethodHandlers`/`PromptMethodHandlers`/`ToolMethodHandlers` filter both `*/list` results
and direct reads to only the extensions the current session negotiated
(`context.isExtensionEnabled`), so a feature carrying an `extensionId` with no matching negotiated
extension becomes silently invisible — a footgun for ordinary server authors who'd never touch it
otherwise. It never appears on the wire either way. The one real caller
(`TasksExtension.java`) sets it via the raw Java `*Descriptor.Builder` in its own bootstrap code —
extension implementations are the intended and only audience. Kotlin still exposes it, but only
on the `*DescriptorScope`/`*Builder` DSL classes (`ToolDescriptorScope`, `ResourceDescriptorScope`,
`ResourceTemplateDescriptorBuilder`, `PromptDescriptorScope`), marked `@ExperimentalApi` to signal
it's for advanced/extension use: `resourceDescriptor(name, uri) { extensionId = MY_EXT_ID }` then
`resource(descriptor) { }` — never `resource(name, uri, extensionId = ...) { }`.

## ⚠️ `_meta` stays out of the ergonomic surface

`_meta` is the MCP runtime's protocol envelope — `progressToken`, reserved `io.modelcontextprotocol/*` keys, OpenTelemetry trace context — growing every protocol revision; implementations **must not** assume meaning for reserved keys (MCP spec, `_meta` section). Don't add `meta()` to an ergonomic type (`Args` and friends) — invites Hyrum's-law coupling to runtime internals, same failure mode as an `Internal*`-named type users are forced to hold.

A function reads arguments and request metadata through `ToolRequest`; subclass the experimental
`AbstractToolHandler` only when a function cannot express the implementation.

## ⚠️ `Optional<T>` vs `@Nullable` — pick by contract, not habit

JSpecify `@Nullable` is the baseline (`@NullMarked` at package level). Reach for `Optional<T>` only when absence is a first-class, must-handle part of the contract — not as a blanket null-replacement. Oracle's own `Optional` docs: primarily a method return type for a clear "no result" case where `null` would likely cause bugs, not a general substitute for every nullable value.

- `@Nullable T` — ordinary nullable state, cache/map-style `get`, DTO fields: `@Nullable String description()`, `@Nullable Value get(Key key)`, `session()`, `sessionId()`.
- `Optional<T>` — a lookup/search where "no result" is the point: `Optional<User> findUserById(id)`, `Optional<Path> resolveConfigFile()`.
- Collections: return empty, never wrap a `List`/`Map` in `@Nullable` or `Optional`.
- Avoid `Optional` for plain field-style getters (`Optional<String> getName()`), setters (`void setName(Optional<String>)`), or class fields — usually just awkward, rarely earns its ceremony.
- Parameters: `@Nullable T`, never `Optional<T>` — including optional arguments of `@McpTool`/`@McpPrompt`/`@McpResource` methods in docs, examples, and tests. `Optional` parameters stay supported for users; tests keep one only to cover that binding.
- Applied here: `InteractionContext.get(AttributeKey<T>)` is `Optional<T>` (map-style lookup, absence is a real branch); `sessionId()` stays `@Nullable String` (ordinary status field, same shape as `lifecycle()`/`session()`).

## 🐛 Context extension state is `AttributeKey<T>`, never `Map<String, Object>`

A stringly-typed attribute bag has two failure modes that compile clean and break at runtime — an unchecked cast on every read (`getAttribute(String)` lets the caller pick any `T`, wrong guess throws `ClassCastException` far from the write site), and silent collision (two unrelated features reusing the same string key overwrite each other). `AttributeKey<T>` fixes both: the key carries `T` so there's no cast at the call site, and keys are identity-based (`AttributeKey.of(name)` returns a distinct object per call, no name-interning registry) so two keys can never collide — retrieval requires holding the actual key instance, not guessing a string. Use this for any future context-carried extension/scratch state; don't reintroduce a generic `Map<String,Object>` surface on a handler-facing type.

Testing handler dispatch/error mapping: see [`tachyon-development` skill](../../.agents/skills/tachyon-development/SKILL.md).

## Request cancellation

For session-based requests, `notifications/cancelled` targets only inbound work with the same
session and request ID. Server-to-client requests have separate tracking, so overlapping IDs do
not cross request directions. Initialize requests are not cancellation targets.
Cancellation interrupts synchronous handler invocation and cancels asynchronous handler futures.
Handlers must cooperate with interruption or future cancellation; external work is application-owned.
Every terminal completion removes the matching tracking entry, including direct future cancellation.
