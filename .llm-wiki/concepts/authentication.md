---
title: Authentication
tags: [concept, security, transport]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/security/, tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/InteractionContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/security/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/SecurityConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/AuthenticationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/ChannelContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/DefaultChannelContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/DefaultDispatchContext.java, tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java]
updated: 2026-09-25
commit: cbfbcd7f
---

# 🔐 Authentication

Verdict: opt-in, per request. No provider ⇒ no handler installed, every caller `SecurityContext.anonymous()`. Provider set ⇒ every aggregated HTTP request authenticated on the handler executor (VT), result copied into its dispatch context. No thread-local holder, session never authenticates.

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
5. Stateful `initialize`: `McpDispatcher#dispatchInitializeAsync` records the creator via `Session#securityContext(SecurityContext)` (set once; process-local, a restored session reads anonymous). Not used to authorize anything yet.

## 🚫 Rejections

| Case | Response | Proof |
|---|---|---|
| no `Authorization`, or non-Bearer scheme | 401 `WWW-Authenticate: Bearer` | `BearerTokenAuthenticationProvider#isBearer` |
| several `Authorization` headers, `access_token` query param | 400 `Bearer error="invalid_request"` | `BearerTokenAuthenticationProvider#authenticate` |
| token not `b64token`, verifier rejects | 401 `Bearer error="invalid_token"` | `BearerTokenAuthenticationProvider#B64TOKEN` |
| provider throws anything else | 500, no challenge, WARN | `AuthenticationHandler#reject` |

Empty body, request CORS decision applied, keep-alive off. Logs carry the path only (`AuthenticationHandler#path`): the query may hold a token.

⚠️ Runs post-aggregation, after Origin/version/Accept/Content-Type/body-size guards ⇒ those answer before a 401, and an unauthenticated body is buffered up to `maxContentLength`.

Tests: e2e `v2026_07_28/BearerAuthenticationTest` (VT verifier, per-request identity on one pooled client, every rejection row), `v2025_11_25/BearerAuthenticationSessionTest` (creator recorded, session no substitute for a token, handler sees request caller). Fixture `TestBearerTokens`.

Related: [[security-guards]], [[sessions]], [[configuration]].
