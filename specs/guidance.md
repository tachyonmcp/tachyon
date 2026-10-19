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
Stack, virtual threads and nullability: [tech-stack.md](tech-stack.md).

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

Tasks live in `tachyon-extensions-tasks`. Register `TasksExtension` by class; its builder takes the
connector. There is no capability switch, no root `ServerBuilder.taskExecutionEngine(...)` setter, and
no instance registration (`withExtensions` rejects a `ConfigurableExtension`):

```java
ServerBuilder withExtension(Class<TasksExtension> type, Consumer<TasksExtension.Builder> configurer);
```

Registering the extension is the only switch: it advertises the `io.modelcontextprotocol/tasks` wire
extension, installs the task methods, and binds the connector in one place. Building must reject a
missing connector and a task-producing tool without the extension.

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

`awaitResult` may block in the external client's supported wait operation. Without it, Tachyon
serves the blocking `tasks/result` by calling `get` until the task is terminal, sleeping for the
snapshot's `pollInterval` (else `resultPollInterval`, default 1s) between calls. The wait has no time
limit: it ends with "Task has expired" once the task `ttl` elapses, or silently once the response is
undeliverable (`InteractionContext#responseUndeliverable`). MCP 2025-11-25 requires `tasks/result`
for every accepted task, so a modern-only connector must still serve legacy clients.

The Tasks facade owns only projection publication and lookup:

```java
public interface Tasks {

    TaskSnapshot publish(TaskSnapshot snapshot);

    @Nullable
    TaskSnapshot get(String taskId);

    boolean remove(String taskId);
}
```

`publish` upserts the cache, applies retention policy, and emits any negotiated MCP notification.
It does not start work. The task-augmented tool call's result gives the task its push route (session
and progress token), set once; never infer a route from the calling thread, and never re-route. A
route is delivery, not access: Tachyon never gates `tasks/*`, the connector authorizes each call
from `InteractionContext`. A later owner (authenticated principal) is a separate field, not the
route. `TaskConnector.get()` is
authoritative for `tasks/get`; a successful
snapshot is published before mapping the response. A refresh failure must not silently invent a
state. A cached snapshot may be used only under an explicit stale-read policy.

Configure one connector while registering the extension. The connector is an infrastructure
dependency, not a task executor or task-handler registration:

```java
var server = TachyonServer.builder()
        .withExtension(TasksExtension.class, t -> t.connector(taskConnector))
        .port(8080)
        .build();
```

Kotlin follows the Java source of truth:

```kotlin
buildServer {
    tasks(taskConnector)
}
```

Kotlin builder task configuration requires `import dev.tachyonmcp.kotlin.server.config.tasks`.

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

- Preserve explicit `@file:JvmName` facade names when adding `@file:JvmSynthetic`; Java source visibility and JVM binary ownership are separate concerns.
- Structured object factories with more than three fields use one canonical type-named receiver builder: `Icon { src = "..."; mimeType = "image/svg+xml" }` (`Annotations` follows this shape despite having three fields — it's nested metadata commonly composed inside other builders). Don't duplicate it with lowercase receiver factories or flat overloads; an owned enclosing DSL may add a singular member such as `argument { }` that delegates to the canonical factory. Required builder fields start nullable and fail with `requireNotNull` in `build()`.
- Type-named receiver factories are plain `public fun`, declare an `EXACTLY_ONCE` contract, and suppress `FunctionName`. Their public builder has a plain `internal` constructor and `build()`.
- `inline` is reserved for `reified` type parameters. A `public inline fun` copies its body into consumer bytecode and forces every member it touches to `@PublishedApi internal` — `public final` in the jar, frozen forever. A configuration DSL saves one `Function1` allocation at construction time; that is never worth the leaked ABI. `callsInPlace` contracts work without `inline`, so definite assignment and smart casts survive.
- Keep core DSL operations as receiver-class members when the receiver is owned by this module. Optional skills/tasks configuration lives in imported `TachyonServerBuilder` extensions, sharing a file with its scope and delegating to `withExtension`. Other top-level extensions operate on types that cannot own the operation. Type-named factories remain top-level when the Java model has no Kotlin companion.
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

Native annotated methods may explicitly opt into request metadata with `@Meta`; it is excluded
from argument schemas. See [parameter binding](../docs/annotations.md#explicit-parameter-names-and-request-metadata).

A function reads arguments and request metadata through `ToolRequest`; subclass the experimental
`AbstractToolHandler` only when a function cannot express the implementation.

## 🐛 Context extension state is `AttributeKey<T>`, never `Map<String, Object>`

A stringly-typed attribute bag has two failure modes that compile clean and break at runtime — an unchecked cast on every read (`getAttribute(String)` lets the caller pick any `T`, wrong guess throws `ClassCastException` far from the write site), and silent collision (two unrelated features reusing the same string key overwrite each other). `AttributeKey<T>` fixes both: the key carries `T` so there's no cast at the call site, and keys are identity-based (`AttributeKey.of(name)` returns a distinct object per call, no name-interning registry) so two keys can never collide — retrieval requires holding the actual key instance, not guessing a string. Use this for any future context-carried extension/scratch state; don't reintroduce a generic `Map<String,Object>` surface on a handler-facing type.

Testing handler dispatch/error mapping: see [`tachyon-development` skill](../.agents/skills/tachyon-development/SKILL.md).

## 🔴 Security context is per request, passed explicitly

`InteractionContext#securityContext()` is the caller of **this** request. Never read identity from a thread-local, the session, or the connection:

- **No holder.** A `ThreadLocal` owner broke async task ownership (#393); Spring's `SecurityContextHolder` needs delegating executors for the same reason. The caller is a final field of the request's dispatch context, so handler work on other threads keeps its caller.
- **Not the session.** MCP security best practices: servers MUST NOT use sessions for authentication. `Session#securityContext()` is the creator, for binding checks only.
- **Not the connection.** A gateway pools one connection across callers, and on a stateful 2025-11-25 connection a single `ChannelContext` serves every request, so it holds no caller at all: it has no setter, and `securityContext()` on it throws `IllegalStateException` instead of answering anonymous, which would hide the mistake. A mutable per-connection slot that is "safe because `HttpPipeliningGate` admits one request at a time" is a `ThreadLocal` in disguise, and a stale value would leak across pooled connections the way a stale holder leaks across pooled threads. Instead `AuthenticationHandler` records the result with `ChannelHandlerUtils#recordCaller`, and the handler that processes the request takes it exactly once, at its entry, for every method (`ChannelHandlerUtils#takeCaller`, `getAndSet(null)`), then passes it down explicitly: the session gate, `McpDispatcher#dispatchRequestAsync` and `#dispatchNotification`, initialize, and the expiry of a stream. With authentication on and no caller recorded it answers `500` and processes nothing. `McpDispatcher` has no overload that defaults the caller: a direct call (a test) states `SecurityContext.anonymous()` itself. Anything that hands a context to user code (a `SessionIdGenerator`, an extension) must hand it the request's `DispatchContext`, never the channel's, or `securityContext()` throws.
- **Immutable, no credentials, no third state.** `SecurityContext` is sealed (anonymous or authenticated), so `isAuthenticated()` cannot disagree with `principal()`. It never carries the token (MCP forbids token passthrough). Anonymous is `SecurityContext.anonymous()`, never `null`, and is never authenticated. Extra verified claims go on the user's own `Principal` subtype, which a `VerifiedToken` or a custom provider hands over unchanged; nobody implements `SecurityContext`.
- **Handler authors touch `tachyon-api`; configuration lives in `tachyon-core`.** `tachyon-api` holds `SecurityContext`, `InteractionContext#securityContext()`, `ToolDescriptor#requiredScopes` and `@McpTool(requiredScopes)`. `AuthenticationProvider`, `AuthenticationRequest`, `AuthenticationException`, `AuthenticationChallenge`, `BearerTokenVerifier` and `VerifiedToken` are configured through `SecurityConfig` and live in `dev.tachyonmcp.core.server.security`, as `SessionStore` and `ObservationListener` do.
- **Providers run off the event loop.** `AuthenticationHandler` calls the provider on the handler executor. `AuthenticationException` rejects the caller (`401`/`400`/`403`) or says the check cannot be made right now (`unavailable`: `503`, with the server's `RuntimeConfig#retryAfter` if set); any other exception is a bug in checking (`500`).
- **Security rejections are unchecked, and mapped in one place.** `AuthenticationException` is a `RuntimeException`, so a policy or a handler can refuse the caller late without a `throws` clause; it is the one rejection type, and its factories cover `401`, `400`, `403` (`insufficientScope`, carrying the scopes, not a challenge) and `503` (`unavailable`; the server adds its one `Retry-After`). `AuthenticationProvider#authenticate` and `BearerTokenVerifier#verify` keep `throws Exception`: anything else they throw is the `500` "bug in checking". `SecurityRejection#of` alone turns it into status, challenge, `Retry-After` and `OperationOutcome`, asking `BearerTokenAuthenticationProvider#challengeFor` for the Bearer challenge of a `401` or `403`, for `AuthenticationHandler#reject` on the head and `McpDispatcher#handleHandlerError` after it; `HandlerFutures#invokeAndMap` lets them through instead of mapping them to a feature error. Never route a rejection through Netty `exceptionCaught` (it logs ERROR and closes, without the request's CORS decision). Once a response has streamed its head, the dispatcher answers a JSON-RPC error instead of a status.
- **What a caller may see is one check.** `AccessPolicy` (core) is required when requests are authenticated (`SecurityConfig.Builder#build` throws otherwise; `permitAll()` is the explicit choice) and defaults to permit-all without authentication. `Visibility#of` (negotiated extension AND policy) is the only check, used by every list and every path that names a feature; a hidden feature answers like an unknown one, never with a distinct error. Scopes are not visibility: `requiredScopes` stays a step-up (listed, `403`).
- **The provider contract names no transport.** `AuthenticationProvider` takes an `AuthenticationRequest`, a read-only view of method, URI and headers with no Netty type and no mutator, and is not generic: there is one transport, and a type parameter would be speculation. Spring splits servlet and reactive security into two parallel APIs and Jakarta Authentication (JASPIC) is tied to the servlet; keep identity off the transport API for the same reason.
- **Authorization is declared on the descriptor, not coded in the handler.** `ToolDescriptor#requiredScopes` is server-side policy: it is not mapped to the wire `Tool`, so it is neither in `tools/list` nor in `_meta`. `ToolsCallHandler` checks it right after the lookup by the name the decoded body carries (never a mirror header) and before the arguments are validated, and signals a failure with `AuthenticationException#insufficientScope`; the dispatcher turns that into `DispatchResult.Denied`, which the transport writes as `403` with the Bearer `insufficient_scope` challenge (a custom provider gets no challenge). It is never a JSON-RPC error: the spec ties it to an HTTP status. A scope nobody can hold is unrepresentable: registering a tool with required scopes on a server with no authentication throws, and a scope that is not an RFC 6749 §3.3 token is refused by `DefaultToolRegistry#register` (the descriptor is data: only registration knows whether the server can ever satisfy it). Hierarchies stay the application's business (put the implied scopes in the verifier's `scopes`); there is no hook. `McpInitializationHandler#completeDispatch` switches on `DispatchResult` exhaustively, so a new outcome cannot be forgotten there; `McpOperationHandler#completePostRequest` still ends in a cast (see `../.llm-wiki/findings.md`).
- **The rejection carries its own challenge.** `AuthenticationException` is built by factories that pair status and `WWW-Authenticate`, so an inconsistent rejection cannot be represented: a `401` requires an `AuthenticationChallenge` (RFC 9110 §11.6.1), whose scheme and parameter names are validated tokens and whose values are quoted and escaped for you. The handler writes what the exception carries and knows no scheme. The `bearer…` factories (`bearerInvalidToken`, `bearerInvalidRequest`) carry the scheme in their names, so a provider for another scheme does not reach for one by accident; there is no bare `Bearer` one, since RFC 6750 §3 puts at least one auth-param after the scheme. Spring's `AuthenticationEntryPoint` separates the challenge from the provider for the same reason.

## Request cancellation

For session-based requests, `notifications/cancelled` targets only inbound work with the same
session and request ID. Server-to-client requests have separate tracking, so overlapping IDs do
not cross request directions. Initialize requests are not cancellation targets.
Cancellation interrupts synchronous handler invocation and cancels asynchronous handler futures.
Handlers must cooperate with interruption or future cancellation; external work is application-owned.
Every terminal completion removes the matching tracking entry, including direct future cancellation.
