---
title: Authentication
tags: [concept, security, transport]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/security/, tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/InteractionContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/security/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/SecurityConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/AuthenticationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/ChannelContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/DefaultChannelContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/DefaultDispatchContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionSnapshot.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/security/Owner.java, extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/engine/TaskEngine.java]
updated: 2026-10-15
commit: 1f2c7fa8
---

# 🔐 Authentication

Verdict: opt-in, per request. No provider ⇒ no handler installed, every caller `SecurityContext.anonymous()`. Provider set ⇒ every aggregated HTTP request authenticated on the handler executor (VT), result copied into its dispatch context. No thread-local holder, session never authenticates. A 2025 session is **bound to its creator**: any other caller ⇒ `404 Unknown session`.

## 🧬 Types

| Type | Role | Proof |
|---|---|---|
| `SecurityContext` | immutable caller: nullable `principal`, plain `scopes`; anonymous never authenticated; carries no credentials | [SecurityContext](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/security/SecurityContext.java), `AnonymousSecurityContext`, `AuthenticatedSecurityContext` |
| `AuthenticationProvider<T>` | `T` = Netty `HttpRequest` on HTTP (same pattern as `SessionIdGenerator<T>`); `throws Exception` | [AuthenticationProvider#authenticate](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/security/AuthenticationProvider.java) |
| `AuthenticationException.Reason` | `MISSING_CREDENTIALS` / `INVALID_REQUEST` / `INVALID_TOKEN` → RFC 6750 §3.1 response | [AuthenticationException](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/security/AuthenticationException.java) |
| `BearerTokenVerifier` | token → context; never sees the request | [BearerTokenVerifier#verify](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/security/BearerTokenVerifier.java) |
| `BearerTokenAuthenticationProvider` | extracts the token, calls the verifier | [BearerTokenAuthenticationProvider#authenticate](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/security/BearerTokenAuthenticationProvider.java) |
| `SecurityConfig` | `ServerBuilder#security`; `bearerToken(verifier)` wraps the provider | [SecurityConfig.Builder#bearerToken](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/SecurityConfig.java) |

## 🔁 Flow

1. `McpChannelInitializer#initChannel` adds `authentication` after `interaction` only when `SecurityConfig#authenticationProvider` is non-null → [[netty-pipeline]].
2. `AuthenticationHandler#channelRead` takes the `FullHttpRequest`, runs the provider on `ServerEngine#executor`, resumes on the event loop (`AuthenticationHandler#resume`).
3. Success ⇒ `ChannelContext#setSecurityContext`, request fires on. Safe per connection only because `HttpPipeliningGate` admits one request at a time.
4. `DefaultDispatchContext#DefaultDispatchContext` **copies** `channel.securityContext()`; handlers read `InteractionContext#securityContext` (default method, anonymous). Continuations on other threads keep their request's caller.
5. **`Owner`** is the one definition of who owns state that outlives a request: `Owner#anonymous` or `Owner.Named(name)` (name = `Principal#getName()`, never blank). Always derived from a request's `SecurityContext` by `Owner#of`, never passed beside a context. Two questions, named differently on purpose: `equals` = same caller (sessions: an anonymous session serves only anonymous callers); `Owner#admits` = may this caller reach what the owner owns (tasks: anonymous admits everyone, a named owner only itself) [Owner](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/security/Owner.java). `Owner#key` / `Owner#fromKey` is the persisted form, converted at one boundary (`Session`).
6. Stateful `initialize`: `McpDispatcher#dispatchInitializeAsync` passes the request's `SecurityContext` to `ServerEngine#createSession`, which derives the `Owner` and hands its key to `SessionStore#create`. The owner is in the first persisted `SessionSnapshot#ownerKey`, final in `Session#owner`, copied by every later snapshot (`Session#snapshot`, `SessionManager#refreshed`, `InMemorySessionStore#touched`), so a restored session keeps it → [[sessions]].
7. Session binding: `McpOperationHandler#ownedBy` is the one gate for every request that names a session. The caller's `Owner` is fixed on the event loop before the async hop (`McpOperationHandler#callerOf`), never read again from the channel. POST lookup (request, notification, client response/error, `notifications/cancelled`) in `McpOperationHandler#parseAndDispatchPost`; GET stream and `Last-Event-ID` resume in `McpOperationHandler#handleGet` (local and restored); `McpOperationHandler#handleDelete`. `Session#isOwnedBy` is `Owner#equals`. Mismatch ⇒ empty lookup ⇒ the same `404 Unknown session` as an unknown id (`McpOperationHandler#sendUnknownSession`), WARN with a truncated id, session untouched. Stateless servers and 2026-07-28 never reach it: no session id, no GET stream, `StatelessValidatorHandler` answers first.
8. Task binding: a task-producing tool call publishes through `TaskRuntime#publish(DispatchContext, …)`, so the owner comes from the request's own context copy, safe after an async tool left the dispatch thread. `TaskEngine#publishAs` binds it; `TaskEngine#connector` is the guarded connector every caller-facing task path goes through → [[tasks]].

## 🚫 Rejections

| Case | Response | Proof |
|---|---|---|
| no `Authorization`, or non-Bearer scheme | 401 `WWW-Authenticate: Bearer` | `BearerTokenAuthenticationProvider#isBearer` |
| several `Authorization` headers, `access_token` query param | 400 `Bearer error="invalid_request"` | `BearerTokenAuthenticationProvider#authenticate` |
| token not `b64token`, verifier rejects | 401 `Bearer error="invalid_token"` | `BearerTokenAuthenticationProvider#B64TOKEN` |
| provider throws anything else, or returns a principal without a name | 500, no challenge, WARN | `AuthenticationHandler#reject`, `AuthenticationHandler#authenticate` |
| 2025 session id used by another caller (POST, GET, `Last-Event-ID`, DELETE) | 404 `Unknown session`, session survives | `McpOperationHandler#ownedBy` |
| task owned by another caller: `tasks/get`, `result`, `cancel`, `update` | `-32602` `Task not found`, identical to an unknown id; the real connector never runs; `tasks/list` omits it | `TaskEngine#connector` |
| a publish that would change a task's owner | tool path: generic `-32603 Internal error`; WARN with a truncated id; nothing about the task changes | `TaskEngine#publishAs` |

Empty body, request CORS decision applied, keep-alive off. Logs carry the path only (`AuthenticationHandler#path`): the query may hold a token.

⚠️ Runs post-aggregation, after Origin/version/Accept/Content-Type/body-size guards ⇒ those answer before a 401, and an unauthenticated body is buffered up to `maxContentLength`.

Tests: e2e `v2026_07_28/BearerAuthenticationTest` (VT verifier, per-request identity on one pooled client, every rejection row), `v2025_11_25/BearerAuthenticationSessionTest` (owner recorded, session no substitute for a token, another caller refused on POST/DELETE/GET/`Last-Event-ID`/responses/cancel, creator's open stream survives), `v2025_11_25/MixedAuthenticationSessionTest` (anonymous session vs authenticated caller, both directions), `v2025_11_25/SessionOwnerRestoreTest` (two nodes, one store), `SessionManagerTest#ownerIsPersistedFromCreationAndSurvivesEveryRevisionAndRestore`. 2026: `BearerAuthenticationTest#thereAreNoSessionsToOwnSoGetAndDeleteNeverReachAnOwnerCheck`. Tasks: `v2025_11_25/TaskOwnerBindingTest`, `v2026_07_28/TaskOwnerBindingTest`, `TaskOwnershipTest` (engine). Fixtures `TestBearerTokens`, `OwnedTasksFixture`.

Related: [[security-guards]], [[sessions]], [[configuration]].
