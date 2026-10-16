# Streamable HTTP authorization: plan

Design as built, for [requirements.md](requirements.md): IDs (K12, S1, V1, …) and § numbers refer to its rows and sections. Acceptance: [validation.md](validation.md).

## API

As built (the names the code has; this section lists no planned shape):

- **Module split.** `tachyon-api` keeps what a handler author touches: `SecurityContext` (sealed: anonymous or authenticated, nothing between; extra claims go on the user's own `Principal` subtype), `InteractionContext#securityContext()`, `ToolDescriptor#requiredScopes` and `@McpTool(requiredScopes)`. Everything configured through `ServerBuilder#security` lives in `tachyon-core`, package `dev.tachyonmcp.core.server.security`: `AuthenticationProvider`, `AuthenticationRequest`, `AuthenticationException`, `AuthenticationChallenge`, `BearerTokenVerifier`, `VerifiedToken` (and `ScopeTokens`, `@InternalApi`). Their implementors already depend on core for `SecurityConfig`.

- `ServerBuilder#security(Consumer<SecurityConfig.Builder>)`. `SecurityConfig(Authentication authentication, Duration authenticationTimeout)`, where `SecurityConfig.Authentication` is sealed: `Disabled`, `Bearer` (its provider is derived from its `BearerConfig` and the server clock, and cannot be supplied) and `Custom(AuthenticationProvider)`. The builder's `bearerToken(Consumer<BearerConfig.Builder>)`, `authenticationProvider(provider)` and `authenticationTimeout(Duration)`: the last of the first two wins.
- `BearerConfig(resource, authorizationServers, scopesSupported, verifier)`: every rule (D1, D4, D5) is checked in its constructor, when `bearerToken(...)` is called.
- `AuthenticationProvider#authenticate(AuthenticationRequest)` returns a `SecurityContext` or throws `AuthenticationException`; it runs on the handler executor, **never on the event loop**, because introspection may block. `AuthenticationRequest` is a read-only view of the request head; `AuthenticationException` and `AuthenticationChallenge` build every rejection (K11).
- `BearerTokenVerifier#verify(String)` returns `VerifiedToken(Principal principal, Set<String> scopes, Instant expiresAt, Set<URI> audience)`: `expiresAt` is required, and an audience that may be empty is then never accepted (K4, K5).
- `InteractionContext#securityContext()` returns the caller of **this** request (K12): `principal()` (`null` when anonymous), `scopes()`, `expiresAt()`.
- `ToolDescriptor#requiredScopes()`, also `@McpTool(requiredScopes)` (S1).
- The owner of a session or a task is `Owner.of(SecurityContext)`: the principal's `getName()`, or anonymous. It is not configurable (❓ below).
- `SecurityConfig.Builder#accessPolicy(AccessPolicy)`: built, as `AccessPolicy` (V1), in place of the `AuthorizationPolicy` once sketched. Scope hierarchies have no `ScopeImplication` type: the verifier expands the scopes (S3).
- Not built, and not planned in this shape: `ServerBuilder#authorization(...)`, `TokenVerifier`, a Kotlin DSL and a Spring Boot property surface for `security`.

## Pipeline

`http` → `http-pipelining` → `http-keep-alive` → `dns-rebinding` (H1) → `mcp-endpoint` (lets the metadata paths through when bearer tokens are on) → `cors-mcp-param` → `cors` (preflight passes without a token) → ✅ **`protected-resource-metadata`** (D2, only with bearer tokens; consumes every request for its paths) → ✅ **`authentication`** (§2, on the request head; records the caller, K12) → `mcp-header-guard` → `protocol-version` → `accept-header` → `content-type` → `stateless-mcp` (stateless servers) → `http-aggregator` → `unsupported-protocol-version` → `interaction` → `idle` → `mcp-header-match` (L2) → the protocol's own request handlers → `mcp-phase-init` or `mcp-phase-operations`: ✅ **takes the caller** (K12), then session lookup + **binding** (§3) → decode → ✅ **scope check** (S1: `ToolsCallHandler`, after the tool is found, ahead of argument validation) → dispatch.

A 401/403 answers before any session lookup, SSE upgrade, replay or listen. A refused request is answered from its head: no body is read, `100 Continue` is never sent, and the connection closes. With authentication off, `authentication` and `protected-resource-metadata` are not installed, the handler that processes the request takes an anonymous caller, and the rest of the pipeline is unchanged (`HttpRequestPathBenchmark#authOff`).

## ❓ Open

- 2025: fan push out to every live session of the owner, instead of only the originating one?
- `notifications/*/list_changed` is broadcast to every session / listener: scope it per principal when per-principal lists differ?
- Cache `VerifiedToken` per token hash, bounded by `expiresAt`?
- Configurable owner key: today it is `Principal#getName()` (`Owner#of`). Multiple issuers need an owner-key function on `security(...)`, for example `(client_id, iss, sub)`. Sessions and tasks both read it, so it must be one function.
- A Kotlin DSL `security { }` and a Spring Boot property surface for authentication; a bridge from Spring Security's `Authentication` (already a `Principal`). Only the Java `ServerBuilder#security` exists.
- Scopes (S1): `resources/read`, `prompts/get` and other methods have no `requiredScopes`; `tools/list` is not filtered by a caller's scopes (S4: scopes are a step-up; the access policy hides, V1); a tool's scopes are not checked against `BearerConfig#scopesSupported`; the Kotlin flat `tool(...)` overloads do not take `requiredScopes`; scope hierarchies (S3) are the application's, expressed by expanding the scopes it reports.
- Discovery (D2): a custom provider publishes nothing and has no Protected Resource Metadata; a gateway that rewrites the path prefix must forward the well-known path unchanged, because the handler matches the public resource path.
- Re-run `HttpRequestPathBenchmark`, `ToolCallDispatchBenchmark` on a quiet machine before release (every JMH A/B on this branch ran under load; see `../../../.llm-wiki/findings.md`).

## Prior art

| SDK | Type | Key | Binds |
|---|---|---|---|
| TypeScript | `AuthInfo { token, clientId, scopes, expiresAt, resource, extra }` on `ctx.authInfo` | `clientId` | request state |
| Python | `AccessToken { client_id, scopes, subject, claims }`, `get_access_token()` | `AuthorizationContext { client_id, issuer, subject }` | session owner: strict equality, mismatch ⇒ `404` (`streamable_http_manager.py`) |
| Go | `auth.TokenInfo { UserID, Scopes, Expiration, Extra }` | `UserID` | session owner: checked only when set, mismatch ⇒ `403` (`mcp/streamable.go`) |

None of these SDKs binds tasks yet: on main, tasks are absent or marked deferred. The TS SDK exposes the raw token to handlers; Tachyon does not (K6).
