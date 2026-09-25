# Streamable HTTP authorization

🎯 Tachyon meets the Streamable HTTP rule "Servers **SHOULD** implement proper authentication for all connections". Every HTTP request is authenticated. State that outlives a request (2025-11-25 sessions, GET streams, replay; 2026-07-28 `subscriptions/listen`; tasks) is bound to the caller's principal. Without authorization configured, behaviour is unchanged. The application verifies tokens; Tachyon is never the authorization server.

Sources:

- **[H25]** [2025-11-25 Transports § Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http)
- **[H26]** [2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- **[A25]** [2025-11-25 Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- **[A26]** [2026-07-28 Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization), plus [§ Discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery) and [§ Security](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)
- **[SBP]** [2025-11-25 Security Best Practices](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices)
- **[T25]** [2025-11-25 Tasks § Security](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks#security-considerations); **[T26]** [ext-tasks 2026-07-28 § Security](https://github.com/modelcontextprotocol/ext-tasks/blob/main/specification/2026-07-28/tasks.md#security-considerations)

Owner column: **T** = Tachyon enforces it, **A** = application verifier or policy.

## 1. Transport security warning (both versions)

| # | Requirement | Src | Owner | Tachyon |
|---|---|---|---|---|
| H1 | **MUST** validate `Origin` on all connections; an invalid one ⇒ `403` | H25 §Security Warning, H26 §Security & Endpoint | T | ✅ `DnsRebindingProtectionHandler` |
| H2 | Running locally: **SHOULD** bind to localhost only | same | T | ✅ `NetworkConfig.DEFAULT_HOST = "127.0.0.1"` |
| H3 | **SHOULD** implement proper authentication for all connections | same | T + A | This spec. Authorization is **OPTIONAL** [A25 §1.2], so it is opt-in. |

## 2. Per-request authentication (both versions)

Authorization "**MUST** be included in every HTTP request from client to server" [A25/A26 §Token Requirements]. Every POST, and every 2025 GET and DELETE, is authenticated on its own. A session or an open stream never stands in for a token.

| # | Requirement | Src | Owner | Tachyon |
|---|---|---|---|---|
| K1 | `Authorization: Bearer` header on every request | A25/A26 §Token Requirements | T | Reads the header only. |
| K2 | Tokens **MUST NOT** be in the query string | same | T | An `access_token` query parameter ⇒ `400` `invalid_request`, never read. |
| K3 | **MUST** validate per OAuth 2.1 §5.2 | A25/A26 §Token Handling | A | A `TokenVerifier` is required when authorization is on. |
| K4 | **MUST** validate the audience (RFC 8707) | A25/A26 §Token Handling, A26 §Security | A + T | Tachyon also checks that the verifier-reported audience contains the canonical `resource`. |
| K5 | Invalid, expired or missing token ⇒ `401` + `WWW-Authenticate: Bearer` | A25/A26 §Token Handling, §Error Handling | T | Also checks `expiresAt` itself. |
| K6 | **MUST NOT** accept or pass through other tokens | A25/A26 §Access Token Privilege Restriction, SBP §Token Passthrough | T | Handlers get a `Principal` plus scopes, never the raw token. |
| K7 | Secure token storage; no tokens in server logs | A25/A26 §Token Theft | T | Redact `Authorization`. 🐛 The DEBUG wire `LoggingHandler` dumps it today (`findings.md`). |
| K8 | TLS (OAuth 2.1 §1.5) | A25/A26 §Communication Security | A | TLS is terminated at the API gateway; core has no TLS and does not warn. The gateway **must forward `Authorization` unchanged**. Tachyon validates the token itself (K3–K5) even when the gateway already did: gateway-injected identity headers are never trusted. |
| K9 | Malformed authorization request ⇒ `400` | A25/A26 §Error Handling | T | A bad `Authorization` header, several bearer credentials, or K2. |

### Discovery

| # | Requirement | Src | Owner | Tachyon |
|---|---|---|---|---|
| D1 | **MUST** serve RFC 9728 Protected Resource Metadata (PRM); `authorization_servers` ≥ 1 | A25 §Overview.4, A26 §Discovery | T | PRM JSON from config: `resource`, `authorization_servers`, `scopes_supported`, `bearer_methods_supported: ["header"]`. |
| D2 | `resource_metadata` in `WWW-Authenticate` on 401, **or** a well-known URI | A25/A26 §PRM Discovery | T | Both: `/.well-known/oauth-protected-resource/<mcp-path>` and the root. No auth needed, CORS enabled. |
| D3 | 401 **SHOULD** carry `scope` | A25 §PRM Discovery, A26 §Scope Selection | T | The configured baseline scopes. |
| D4 | **SHOULD NOT** advertise `offline_access` | A26 §Refresh Tokens | T | `build()` rejects it. |
| D5 | Canonical resource URI: no fragment, no trailing slash | A25/A26 §Canonical Server URI | T | Validated at `build()`. It is the **public gateway URL** (`https://…`), configured explicitly: the PRM `resource`, the `resource_metadata` URL in challenges, and the expected audience are never derived from `Host` or `X-Forwarded-*`, because behind the gateway those show the internal address and can be spoofed. |

### Scopes

| # | Requirement | Src | Owner | Tachyon |
|---|---|---|---|---|
| S1 | Insufficient scope ⇒ `403` + `error="insufficient_scope", scope, resource_metadata` | A25/A26 §Runtime Insufficient Scope | T | The `AuthorizationPolicy` runs after body decode and header validation, **before** dispatch and before any SSE bytes. |
| S2 | **SHOULD** name all required scopes in one challenge | A26 §Server Scope Management | A | The policy returns the full set for `(method, name, params)`. |
| S3 | **MUST** account for scope hierarchies | A26 §Step-Up | A | A `ScopeImplication` hook; default = exact match. |

## 3. 2025-11-25: sessions, GET stream, replay

| # | Requirement | Src | Owner | Tachyon |
|---|---|---|---|---|
| X1 | Session id: globally unique, cryptographically secure, visible ASCII | H25 §Session Management | T / A | ✅ `SessionIdGenerator.DEFAULT` (`UUID.randomUUID`). Custom generators: documented. |
| X2 | **MUST NOT** use sessions for authentication; verify all inbound requests | SBP §Session Hijacking | T | §2 runs on every request. |
| X3 | **SHOULD** bind session ids to user-specific information | SBP §Session Hijacking | T | See Session binding. |
| X4 | Session data in stores and queues keyed `<user_id>:<session_id>` | SBP §Session Hijack Prompt Injection | T | `SessionEventStore` keys include the owner key. |
| X5 | Terminated or unknown session ⇒ `404`; missing id ⇒ `400` | H25 §Session Management | T | ✅ Today. A principal mismatch reuses the `404`. |
| X6 | GET stream: server-initiated messages to *this* client | H25 §Listening | T | The GET carries a token; it must match the session owner (X3). |
| X7 | Replay via `Last-Event-ID` **MUST NOT** cross streams | H25 §Resumability | T | ✅ Per-stream today; now also only for the session owner. |
| X8 | **MUST NOT** broadcast one message across streams | H25 §Multiple Connections | T | ✅ Unchanged. |

### Session binding

- A session records the owner key of the request that created it (`initialize`), or `null`. The key is fixed and persisted in `SessionSnapshot`.
- Every later POST, GET, DELETE or `Last-Event-ID` resume with that `Mcp-Session-Id` must resolve to an **equal** key; `null` equals only `null`. This matches Python; Go skips the check for anonymous sessions.
- Mismatch ⇒ `404 Unknown session`, the same response as an unknown id (`McpOperationHandler`), logged at warn with a truncated id. The session is not terminated. Go's `403` would reveal that the session exists.
- The `SessionIdGenerator` Javadoc ("must verify the principal") is replaced by a description of this binding.

## 4. 2026-07-28: stateless POST, `subscriptions/listen`

No protocol sessions, no GET stream, no replay [H26 §Backward Compatibility]. Each POST is self-contained, so §2 alone covers request/response.

| # | Requirement | Src | Owner | Tachyon |
|---|---|---|---|---|
| L1 | GET/DELETE ⇒ `405`; ignore `Mcp-Session-Id` and `Last-Event-ID` | H26 §Earlier Streamable HTTP Revisions | T | ✅ `StatelessValidatorHandler`. Auth adds no state here. |
| L2 | Header values **MUST** match the body; mismatch ⇒ `400` `-32020 HeaderMismatch` | H26 §Server Validation | T | ✅ `McpHeaderMatchHandler`. The policy (S1) reads **validated** values only, never raw `Mcp-Name` / `Mcp-Param-*`. |
| L3 | `subscriptions/listen` stream carries only notifications the client opted in to | H26 §Receiving Messages | T | Notifications are filtered by the listen request's principal. A task-scoped subscription is refused (not an error: the task is simply never delivered) when the task's owner key differs. |
| L4 | Long-lived stream vs token expiry | — (spec silent) | T | Tachyon decision: close the listen stream at `expiresAt`; the client re-listens with a fresh token. |

## 5. Tasks

- **2025-11-25:** "When an authorization context is provided, receivers **MUST** bind tasks to said context" [T25]. Without one, tasks **may be anonymous**: the receiver documents the limitation and uses high-entropy ids. Tachyon does both and keeps declaring `tasks.list` (no breaking change).
- **2026-07-28:** authn + authz on each task-related request; ids may be bearer tokens and **MUST** be unguessable [T26].

| Entry point | Owner key | Notify target | Progress token |
|---|---|---|---|
| Task-augmented `tools/call` returns `ToolResult.task(...)` | caller's, or `null` | calling session (2025) | request's token |
| `InteractionContext#tasks().publish(s)` (new) | caller's, or `null` | calling session (2025) | none |
| `TachyonServer#tasks().publish(s)` | `null` | none | none |

- `publish` caches unknown ids again. The owner comes from the call site, never from the thread.
- **Owned task** (non-null key): reachable only with an equal key, from any session. Others get `Task not found` before the connector runs.
- **Anonymous task** (null key): keeps #393's session isolation on stateful 2025 servers (the creating session only). Stateless servers and server-published tasks go to the connector, which authorizes via `InteractionContext`.
- An owner never changes: a different owner, or anonymous → owned, is refused and logged; the tool path answers the generic `Internal error`. Same owner, newer revision ⇒ update.
- `tasks/get|cancel|result|update` refresh only existing entries; a connector snapshot for an unknown id is never cached.
- Push (`notifications/tasks/status`, `notifications/progress`): 2025 ⇒ the notify session only; 2026 ⇒ `subscriptions/listen` subscribers that pass L3. Never broadcast.

## API

- `ServerBuilder#authorization(a -> a.resource(URI).authorizationServers(URI...).scopesSupported(String...).tokenVerifier(TokenVerifier).policy(AuthorizationPolicy).ownerKey(Function<Principal,String>))`
- `TokenVerifier`: `(String token, request head) -> VerifiedToken`, throws `InvalidTokenException`. It runs on the handler executor, **never on the event loop**, because introspection may block.
- `VerifiedToken(Principal principal, Set<String> scopes, @Nullable Instant expiresAt, Set<URI> audience)`
- `InteractionContext#principal()` → `@Nullable Principal`; `InteractionContext#scopes()` → `Set<String>`.
- Owner key default: `Principal::getName`. Multi-issuer: `(client_id, iss, sub)`, as in Python `principal_components`.

## Pipeline

`http` → `dns-rebinding` (H1) → **`protected-resource-metadata`** (D2) → `mcp-endpoint` → `cors` (preflight passes without a token) → **`authorization`** (§2) → `mcp-header-guard` → `protocol-version` → … → `mcp-header-match` (L2) → session lookup + **binding** (§3) → decode → **policy** (S1) → dispatch.

A 401/403 answers before any session lookup, SSE upgrade, replay or listen.

## ❓ Open

- 2025: fan push out to every live session of the owner, instead of only the originating one?
- `notifications/*/list_changed` is broadcast to every session / listener: scope it per principal when per-principal lists differ?
- Cache `VerifiedToken` per token hash, bounded by `expiresAt`?
- Kotlin DSL `authorization { }`; Spring Boot bridge from Spring Security's `Authentication` (already a `Principal`).

## Prior art

| SDK | Type | Key | Binds |
|---|---|---|---|
| TypeScript | `AuthInfo { token, clientId, scopes, expiresAt, resource, extra }` on `ctx.authInfo` | `clientId` | request state |
| Python | `AccessToken { client_id, scopes, subject, claims }`, `get_access_token()` | `AuthorizationContext { client_id, issuer, subject }` | session owner: strict equality, mismatch ⇒ `404` (`streamable_http_manager.py`) |
| Go | `auth.TokenInfo { UserID, Scopes, Expiration, Extra }` | `UserID` | session owner: checked only when set, mismatch ⇒ `403` (`mcp/streamable.go`) |

None of these SDKs binds tasks yet: on main, tasks are absent or marked deferred. The TS SDK exposes the raw token to handlers; Tachyon does not (K6).

✅ Acceptance (E2E, port 0, both protocol versions unless marked):

- **Unconfigured:** every existing E2E passes unchanged; `tasks.list` is still declared.
- **Discovery:** request without a token ⇒ `401` + `WWW-Authenticate: Bearer resource_metadata="…", scope="…"`. Both well-known URIs serve PRM without a token. With `resource = https://api.example.com/mcp` and a plain-HTTP request whose `Host` / `X-Forwarded-Host` is spoofed, `resource` and `resource_metadata` still name `https://api.example.com`.
- **Tokens:** expired, wrong audience, or bad signature ⇒ `401 invalid_token`. `?access_token=` ⇒ `400`. No log line contains the token, the DEBUG wire log included. Handlers cannot read the raw token.
- **Scopes:** missing scope ⇒ `403 insufficient_scope` with every required scope in one challenge, before any SSE bytes. The policy sees body values, not a forged `Mcp-Name` (2026).
- **2025 sessions:** session created by A, a POST/GET/DELETE from B or anonymous ⇒ `404`; A unaffected. An anonymous session rejects an authenticated caller. `Last-Event-ID` from B ⇒ `404`, nothing replayed. A restored session keeps its owner key.
- **2026 listen:** B's listen never receives A's task status. The listen stream closes at token `expiresAt`.
- **Tasks:** same principal, two sessions (2025) ⇒ `tasks/get` succeeds from both. A different principal ⇒ `Task not found`, connector not called. An anonymous task on a stateful 2025 server ⇒ only the creating session reaches it (#393 unchanged). `ctx.tasks().publish` from a plain tool ⇒ `server.tasks().get(id)` is non-null. A job publishing before its task tool returns ⇒ the call succeeds with the same owner. Anonymous publish, then an owned `create` ⇒ refused.
