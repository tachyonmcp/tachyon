---
title: "Authentication"
weight: 62
sidebar_order: 62
toc: true
description: |-
  Authenticate every MCP request with an OAuth 2.1 bearer token or a custom provider, and read the caller from the handler's SecurityContext.
---

Tachyon can authenticate every HTTP request before it reaches a handler. You supply the token
check; Tachyon extracts credentials, runs your check off the I/O threads, answers rejected requests,
and hands each handler the caller of its own request.

Authentication is off by default: every request is anonymous and no extra handler runs. The API is
experimental.

## Bearer tokens

The [MCP authorization spec](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-usage)
has clients send an OAuth 2.1 access token in `Authorization: Bearer` on every request. Tell Tachyon
the canonical URI of your server, the authorization server that issues tokens for it, the scopes it
understands, and give it a `BearerTokenVerifier`:

```java
var server = TachyonServer.builder()
        .security(security -> security.bearerToken(bearer -> bearer
                .resource(URI.create("https://mcp.example.com/mcp"))
                .authorizationServers(URI.create("https://auth.example.com"))
                .scopesSupported("tools:read", "tools:call")
                .verifier(token -> {
                    var jwt = jwtDecoder.decode(token); // signature and issuer
                    return new VerifiedToken(
                            new UserPrincipal(jwt.subject()),
                            jwt.scopes(),
                            jwt.expiresAt(),
                            jwt.audience().stream().map(URI::create).collect(toSet()));
                }))
                .accessPolicy(AccessPolicy.permitAll()))
        .build();
```

A server that authenticates must also say what its callers may see: without an
[access policy](#access-policy) building it fails, so nothing is left open by forgetting.
`AccessPolicy.permitAll()` is that choice, made on purpose.

A server with bearer tokens always has all of them: the resource, at least one authorization server and
a verifier. `scopesSupported` is the only optional part. A bearer server without an authorization server
could not tell a client where to get a token, so it cannot be configured. Everything is checked when you
configure the server, and a mistake throws `IllegalArgumentException` then, not on the first request.

The verifier checks the token's signature and issuer and reports what it says: who it is for, its
scopes, when it **expires** and who it was **issued to**. Tachyon then makes the two checks the MCP spec
requires and that verifiers most often forget, so you cannot forget them either:

- **Audience.** The token's audience (RFC 8707) must include the server's own resource URI, or a token
  minted for another service would be accepted. `VerifiedToken` has no way to omit the audience: it is a
  required `Set<URI>`, and an empty set is simply never accepted. Leave out audience values that are not
  URIs.
- **Expiry.** A token whose `expiresAt` is not after the server's clock is rejected, with no allowance
  for skew. `expiresAt` is required too; if an introspection response has no `exp` (RFC 7662 makes it
  optional), report the instant until which you trust your answer.

Either failure is `401` with `error="invalid_token"`, the same answer as a bad signature, and the
verifier's output is otherwise used unchanged.

**The resource URI** is the name clients know your server by: the public gateway URL, not the address
behind it. It is configured once, here, and is never derived from the `Host` or `X-Forwarded-*`
headers, which a client can set. It must be an absolute `https` URI (`http` only for `localhost` and
other loopback hosts), without a fragment, without a query and without a trailing slash. A token's
audience matches it with the normalization RFC 3986 §6.2.3 allows: scheme and host in any case, a
default port implied, and an empty path read as `/`. Everything else must be equal, so
`https://mcp.example.com/mcp/` is another resource.

**The authorization servers** are the issuers (RFC 8414 §2) a client may get a token from. Each is an
absolute `https` URI (`http` for a loopback host only) with no query and no fragment. Tachyon publishes
them but does not call them: your verifier decides which tokens it accepts.

**The scopes** are plain OAuth scope tokens (RFC 6749 §3.3). `offline_access` is refused: the MCP spec
has clients decide for themselves whether to ask for a refresh token, and a resource server does not
advertise it.

Throw `AuthenticationException.bearerInvalidToken("...")` for a bad token. Throw anything else
when the token cannot be checked at all, for example when an introspection endpoint is down: that
answers `500`, so the client keeps a token that may still be valid.

| Request | Response |
|---|---|
| Valid token | Continues to the handler |
| No `Authorization` header, or another scheme such as `Basic` | `401`, `WWW-Authenticate: Bearer resource_metadata="…", scope="…"` |
| Malformed, unknown, expired, or issued for another server | `401`, `WWW-Authenticate: Bearer error="invalid_token", resource_metadata="…", scope="…"` |
| Several `Authorization` headers, or `?access_token=` in the URL | `400`, `WWW-Authenticate: Bearer error="invalid_request"` |
| Verifier throws anything else | `500`, no challenge |

Rejected requests have an empty body, never reach a handler, and close the connection. Tachyon never
logs the token, and a token in the query string is refused even when the header is valid.

### Discovery

A client that is refused needs to find out where to get a token. The
[MCP spec](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-discovery)
makes a server publish [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728) Protected Resource Metadata and
say where it is in its `401`. Tachyon does both from the configuration above:

```
$ curl -i https://mcp.example.com/mcp -d '{}' -H 'Content-Type: application/json'
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp", scope="tools:read tools:call"

$ curl https://mcp.example.com/.well-known/oauth-protected-resource/mcp
{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"],"scopes_supported":["tools:read","tools:call"],"bearer_methods_supported":["header"]}
```

- **Where.** The metadata of a resource with a path is served at the well-known segment inserted before
  that path (RFC 9728 §3.1): `https://mcp.example.com/mcp` has
  `/.well-known/oauth-protected-resource/mcp`. The root `/.well-known/oauth-protected-resource` serves the
  same document, as MCP clients may try it as a fallback. Every other path is a `404`, and a server with no
  bearer tokens serves none of it.
- **What.** The resource, the authorization servers, `scopes_supported` (left out when you list none)
  and `bearer_methods_supported: ["header"]`, because tokens are never accepted in the query string. The
  document is rendered once when the server is built.
- **Who.** Anyone, without a token: a client needs it before it has one. `GET` and `HEAD` are allowed
  (`HEAD` answers with the headers of the `GET`, `Content-Length` included, and no body); other methods
  are `405` with `Allow: GET, HEAD`. The Origin and `Host` checks of the DNS-rebinding guard still apply,
  so a page on a foreign origin gets `403`, and the response carries the CORS headers of its request, so
  a browser page on an allowed origin can read it. A browser's CORS preflight to these paths is answered
  by the same [CORS policy](configuration.md#cors) as the MCP endpoint, so a page that sends
  `MCP-Protocol-Version` on the metadata request can discover the authorization server.
- **The challenge.** Every `401` carries `resource_metadata`, the URL above, and `scope` with the scopes
  you list (RFC 9728 §5.1; MCP authorization § Scope Selection), so a client can ask for what the server
  understands. A verifier that throws a `401` of its own gets the same `invalid_token` challenge, so the
  discovery parameters cannot be forgotten. A `400 invalid_request` has none: the request was malformed,
  and finding the authorization server does not fix that.
- **Browsers.** `WWW-Authenticate` is exposed to script through CORS, so a browser client can read the
  challenge.
- **Never from the request.** The URLs come from the configured resource only. A forged `Host`,
  `X-Forwarded-Host`, `X-Forwarded-Proto` or `Forwarded` header changes neither the metadata nor the
  challenge.

A [custom provider](#custom-providers) publishes nothing and challenges as it chooses.

> [!WARNING]
> The one log that can hold a token is the [wire log](observability.md#wire-log) at `TRACE`. It
> hex-dumps every byte on the connection, `Authorization` headers included. Never enable it in
> production.

### Streams end when the token does

A stream must not outlive the token that opened it. When a request's token expires, Tachyon closes every
SSE stream that request opened: the 2025-11-25 `GET` stream, a streamed response to a `POST`, and a
2026-07-28 `subscriptions/listen` stream. It closes them at the expiry and never before, with one timer
per stream and nothing checked per event. The stream ends the way a server ends one it is done with: a
`retry` hint, the final chunk, then the connection. The client connects again with a fresh token. A
2025-11-25 client resumes from its `Last-Event-ID` and loses no event, and a 2026-07-28 client sends
`subscriptions/listen` again, whose result is a new acknowledgement. The MCP spec does not say what a
stream does when its token expires; this is Tachyon's rule.

A tool call whose streamed response is closed at the expiry is **not cancelled**: closing a stream is
not cancelling a call, as for any disconnect. The tool keeps running. Stateful (2025-11-25): the client
can resume the stream and receive the result. Stateless (2026-07-28): nothing can resume it, so
`responseUndeliverable()` completes, which a tool can watch to stop early.

A caller whose context has no expiry, an anonymous caller or one authenticated by your own provider
without an `expiresAt`, has no stream closed. A custom provider opts in with
`SecurityContext.authenticated(principal, scopes, expiresAt)`.

## Custom providers

For API keys or other schemes, implement `AuthenticationProvider`. It receives an
`AuthenticationRequest`: a read-only view of the request head with the HTTP method, the URI as sent
(path and query) and every value of any header, matched case-insensitively. It has no Netty type and
no way to change the request, and it is valid only during the call. The body has not been read yet.

A rejection chooses its own challenge, so a client of your scheme is told how to authenticate in that
scheme and not in Bearer:

```java
var challenge = AuthenticationChallenge.of("ApiKey").withParam("realm", "orders");

.security(security -> security.authenticationProvider(request -> {
            var keys = request.headers("X-Api-Key");
            if (keys.isEmpty()) {
                throw AuthenticationException.unauthorized("No API key", challenge);
            }
            return apiKeys.lookup(keys.getFirst()); // SecurityContext, or throw unauthorized(...)
        })
        .accessPolicy(AccessPolicy.permitAll()))
```

That request is answered `401` with `WWW-Authenticate: ApiKey realm="orders"`.

- `unauthorized(message, challenge)` is a `401`. It takes a challenge because RFC 9110 §11.6.1 requires
  one on every `401`, so a `401` without a challenge cannot be built.
- `badRequest(message)` is a `400` with no challenge; `badRequest(message, challenge)` adds one.
- `bearerInvalidToken` (`401`) and `bearerInvalidRequest` (`400`) are the Bearer answers of
  [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1), with the status that goes with
  each. Use them only for bearer tokens; they always send a `Bearer` challenge with an `error`. There is
  none for a request without a token: RFC 6750 §3 has `Bearer` followed by at least one parameter, so
  state yours, for example `unauthorized(message, AuthenticationChallenge.of("Bearer").withParam("realm", "orders"))`.
- `AuthenticationChallenge` checks what you give it. The scheme and the parameter names must be HTTP
  tokens, a name occurs once per challenge, and a value is quoted for you, with `"` and `\` escaped. A
  value with a line break, a control character or a non-ASCII character is refused with an
  `IllegalArgumentException` that does not repeat it, and the request is then answered `500`, so a value
  from your own code can never add a header.

Return `SecurityContext.anonymous()` to let a request through unauthenticated.

## Required scopes

A tool says which scopes it needs when it is registered, and Tachyon refuses the call before the tool
runs:

```java
server.tools().register(
        d -> d.name("delete-account").requiredScopes("accounts:write"),
        (ctx, request) -> deleteAccount(request));
```

A caller whose token does not carry **every** required scope, compared exactly, gets `403 Forbidden`
with an empty body. The tool never runs, and nothing is streamed first, because the check comes after
the request is decoded and before the handler is called. With bearer tokens the answer names all the
scopes the tool needs, not only the missing ones, and where to find the server's OAuth metadata, so a
client can ask the authorization server for a token that covers them (the MCP spec's step-up flow):

```
HTTP/1.1 403 Forbidden
WWW-Authenticate: Bearer error="insufficient_scope", scope="accounts:write", resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp"
```

This is an HTTP status, never a JSON-RPC error or a `ToolResult.error`, as
[the MCP spec](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
(§ Runtime Insufficient Scope) and [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) have it: a tool error tells the
client nothing it can act on. Do not write `if (!scopes.contains(...))` in a handler for this.

- **Where it is declared.** On the tool: `ToolDescriptor.Builder#requiredScopes`,
  `@McpTool(requiredScopes = {...})`, or `requiredScopes = listOf(...)` in the Kotlin
  `toolDescriptor { }`. It is server-side policy and is not sent to clients: `tools/list` is the same
  for everyone and does not hide the tools a caller cannot call. No scopes declared, no check.
- **What a scope is.** A plain OAuth scope token (RFC 6749 §3.3): no spaces, quotes or non-ASCII
  characters, or registering the tool throws `IllegalArgumentException`.
- **The server must authenticate.** With no `security(...)` configured every caller is anonymous and
  holds no scope, so registering a tool with required scopes throws `IllegalStateException` instead of
  producing a tool nobody can call.
- **A custom provider** gets the `403` without a challenge: `insufficient_scope` is a Bearer error
  (RFC 6750), and your scheme has no such thing. The scopes come from the `SecurityContext` your provider
  returns.
- **Hierarchies are yours.** MCP 2026-07-28 has servers "account for scope hierarchies, where a broader
  scope implies narrower ones". Matching is exact and only you know the hierarchy, so the verifier (or
  your provider) reports the scopes **expanded**: a token that says `files:write`, which implies
  `files:read` in your scheme, is reported with both, and then a tool requiring `files:read` serves it:

  ```java
  .verifier(token -> {
      var jwt = jwtDecoder.decode(token);
      var scopes = new HashSet<>(jwt.scopes());
      if (scopes.contains("files:write")) {
          scopes.add("files:read"); // your hierarchy: write implies read
      }
      return new VerifiedToken(new UserPrincipal(jwt.subject()), scopes, jwt.expiresAt(), audience(jwt));
  })
  ```
- **Scopes never hide a tool.** A tool whose scopes the caller lacks is still in `tools/list`, and the
  call is the `403` above: the MCP spec keeps `scopes_supported` minimal and has more scopes requested
  "incrementally through the step-up authorization flow", which needs the tool to be visible. To hide a
  tool from a caller, use the [access policy](#access-policy).
- **Only `tools/call` today.** Resources, prompts and other methods have no declaration yet.

## Access policy

An `AccessPolicy` decides which tools, resources, resource templates and prompts a caller may see. It
is required as soon as requests are authenticated, and optional without authentication (every caller
is anonymous, and the default lets everything through):

```java
record Staff(String name, Set<String> roles) implements Principal {
    @Override
    public String getName() {
        return name;
    }
}

.security(security -> security.bearerToken(bearer -> bearer /* ... */
                .verifier(token -> {
                    var jwt = jwtDecoder.decode(token);
                    // Resolve roles once, here, and carry them on your own Principal.
                    var staff = new Staff(jwt.subject(), Set.copyOf(jwt.claim("roles")));
                    return new VerifiedToken(staff, jwt.scopes(), jwt.expiresAt(), audience(jwt));
                }))
        .accessPolicy((caller, feature) -> !feature.name().startsWith("admin-")
                || (caller.principal() instanceof Staff staff && staff.roles().contains("admin"))))
```

- **One check, every path.** A feature the policy refuses is left out of `tools/list`,
  `resources/list`, `resources/templates/list` and `prompts/list` (pages stay full and cursors stable),
  and every request that names it is answered **exactly like a request for a feature that does not
  exist**: `tools/call` (`Unknown tool`), `resources/read` by URI or by template (`Resource not found`),
  `prompts/get` (`Prompt not found`), `completion/complete` for the prompt or the resource template
  (no values), a 2025-11-25 `resources/subscribe` (accepted, never delivered) and the resource URIs of a
  2026-07-28 `subscriptions/listen` (acknowledged, never delivered). A probe learns nothing about what
  is hidden.
- **Pure and in memory.** It runs once per listed item and once per request that names a feature, on
  the thread serving the request. Do the I/O at authentication and put the result on the principal.
- **It fails closed.** A policy that throws fails the request with an internal error; nothing is
  served. A policy may throw `AuthenticationException` to answer `401`.
- **The extension still counts.** A feature that belongs to an extension is visible only when the
  extension was negotiated and the policy permits it.

### Trusted clients

With [Client ID Metadata Documents](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration#client-id-metadata-documents)
a client is identified by an HTTPS URL. Tachyon is the resource server and takes no part in
registration; the authorization server puts the client id in the token (`client_id`, or `azp`). Carry
it on the principal, then decide per feature in the policy, or reject the client outright in the
verifier:

```java
record Caller(String name, String clientId) implements Principal { /* getName() returns name */ }

.verifier(token -> {
    var jwt = jwtDecoder.decode(token);
    var caller = new Caller(jwt.subject(), jwt.claim("client_id"));
    if (blockedClients.contains(caller.clientId())) {
        throw AuthenticationException.bearerInvalidToken("Client not allowed"); // the whole server
    }
    return new VerifiedToken(caller, jwt.scopes(), jwt.expiresAt(), audience(jwt));
})
.accessPolicy((caller, feature) -> !feature.name().equals("partner-export")
        || (caller.principal() instanceof Caller c && trustedClients.contains(c.clientId())))
```

### Refusing while serving

`AuthenticationException` is unchecked. A tool, resource or prompt handler, or the policy, can throw
it when it finds out late that the credentials are no good, for example a token revoked upstream. The
request is answered like a refusal on the head: `401` with the challenge; with bearer tokens, the
server's own `invalid_token` challenge, which names the OAuth metadata. Once a response has started
streaming (`200` and `text/event-stream` are already out) a status can no longer be sent, so the client
gets a JSON-RPC error with code `-32600` and message `Unauthorized` (or `Forbidden`) as the last event
of the stream.

## Reading the caller

Every handler reads the caller from its `InteractionContext`. Use it to decide which data a caller may
see; use [required scopes](#required-scopes) to decide which tools they may call:

```java
server.tools().register(
        d -> d.name("my-orders").requiredScopes("orders:read"),
        (ctx, request) -> ToolResult.text(orders.forCustomer(ctx.securityContext().principal().getName())));
```

- `principal()` is `null` and `isAuthenticated()` is `false` for an anonymous caller.
- `scopes()` holds plain OAuth scope strings, without a `SCOPE_` prefix.
- The context never holds the token, so a handler cannot pass it on to other services by mistake.
  Call upstream APIs with their own tokens, as the MCP spec requires.
- `SecurityContext` is sealed: a caller is anonymous or authenticated, never something in between.

To carry more verified claims, give the `Principal` you authenticate with those claims, and narrow
`principal()` to your type in handlers. Tachyon keeps the principal a verifier or provider returned as it
is, in bearer mode and with a custom provider alike:

```java
record Tenant(String name, String tenantId) implements Principal {
    @Override
    public String getName() {
        return name;
    }
}

// in the BearerTokenVerifier: new VerifiedToken(new Tenant(jwt.subject(), jwt.claim("tid")), ...)
// in a custom provider:       SecurityContext.authenticated(new Tenant(key.owner(), key.tenantId()), scopes)

server.tools().register(
        d -> d.name("tenant-orders"),
        (ctx, request) -> ctx.securityContext().principal() instanceof Tenant tenant
                ? ToolResult.text(orders.forTenant(tenant.tenantId()))
                : ToolResult.error("This tool needs a tenant"));
```

## How it runs

- **Per request.** Each HTTP request is authenticated on its own, including requests inside a
  2025-11-25 session. A session never stands in for credentials, as the MCP
  [security best practices](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking)
  require. Two requests on one pooled connection can carry different callers.
- **On the request head.** The provider runs before the body is requested or read. It sees the
  method, URI and headers, never the body. Whatever has already arrived is held, then replayed for an
  accepted request or dropped for a refused one. If the body is still incomplete when a read ends, or
  more than 64 KB of it is held, Tachyon stops reading from the connection until the answer, so a
  caller without a valid token costs its headers plus that bounded amount, never `maxContentLength`.
  A request that arrives whole in one read, such as any request without a body and most small ones,
  pauses nothing. An `Expect: 100-continue` request gets its `401` and not a `100 Continue`, and the
  connection closes after the answer.
- **Off the event loop, and bounded.** The provider runs on the handler executor, virtual threads by
  default, so it may block on introspection or key lookup. A request waits for it at most
  `authenticationTimeout`, 10 seconds by default:

  ```java
  .security(security -> security.bearerToken(bearer -> bearer.resource(resource)
          .authorizationServers(authorizationServer)
          .verifier(verifier))
      .accessPolicy(AccessPolicy.permitAll())
      .authenticationTimeout(Duration.ofSeconds(3)))
  ```

  Past the limit the request is answered `503 Service Unavailable` with `Retry-After` and no
  `WWW-Authenticate`, so the client keeps a token that may be valid, and the connection closes. The
  provider is not interrupted and its late result is ignored, so give its own calls their own
  timeouts. The MCP spec does not define this status; it is a Tachyon decision.
- **No thread-local holder.** The context is passed along with the request. Work a handler hands to
  another thread keeps its own request's caller.
- **Sessions belong to their creator.** A stateful (2025-11-25) session is bound to the caller of the
  `initialize` request that created it, identified by the principal's `getName()`, or by nothing for
  an anonymous caller. Every later request carrying that `MCP-Session-Id` (a POST of any kind,
  the `GET` stream, a `Last-Event-ID` resume and `DELETE`) must come from the same caller, and an
  anonymous session serves only anonymous callers. Any other caller gets `404 Unknown session`, the
  same answer as an unknown id, so a probe learns nothing about which ids exist. The session is left
  untouched and the attempt is logged at `WARN` with a truncated id. Knowing a session id is
  therefore not enough to use, listen to or end the session of someone else. Handlers still see the
  caller of the current request, not the creator.
- **Restored sessions keep their owner.** The owner is stored in `SessionSnapshot`, so a session
  restored from a `SessionStore` on another node or after a restart still serves its creator. A custom
  `SessionStore` must save and return the owner key unchanged.
- **Tasks belong to their creator.** A task created by a task-producing tool call is bound to the
  caller of that call the same way, but from any of its sessions, and on 2025-11-25 and 2026-07-28
  alike. Another caller gets `Task not found` from `tasks/get`, `tasks/result`, `tasks/cancel` and
  `tasks/update`, exactly as for an unknown id and before your task connector runs; `tasks/list`
  leaves it out, and a `subscriptions/listen` stream does not follow it. Tasks an anonymous caller
  creates, or that you publish yourself, have no owner and stay with the connector. The binding is
  kept in memory for the task's `ttl`, so it does not survive a restart and is not shared between
  nodes. **A clustered or restarted server needs a task connector that authorizes every call from
  `ctx.securityContext()`**; Tachyon hands it the caller's own context on every path. See
  [Who can reach a task](../extensions/tasks.md#who-can-reach-a-task).
- **Names identify callers.** The principal's name must be unique and stable for a caller. A provider
  returning a principal with no name, or a blank one, is a provider failure (`500`), because the
  session or task could not be told apart from an anonymous one.

The metadata is served right after the Origin (DNS-rebinding) check and before everything else, so it
needs no token. Authentication runs right after the Origin check, the endpoint check and CORS, and
before every other check on the MCP endpoint. A missing or invalid token therefore answers `401` whatever else is wrong
with the request: an unsupported protocol version, a wrong `Accept` or `Content-Type`, or a body
that is too large. An anonymous caller learns nothing about what the server supports. A preflight
(`OPTIONS`) is answered by CORS and needs no token, and every refusal carries the CORS headers of
its request.

If `readerIdleTimeout` is shorter than `authenticationTimeout`, an idle close can end a connection
whose request is still being authenticated. Keep it longer.

## Not yet built in

- Required scopes for resources, prompts and other methods; only `tools/call` has them.
- Configuring authentication from the Kotlin DSL or Spring Boot properties: only the Java `security(...)` exists.
- Choosing what identifies a caller: sessions and tasks are bound to the principal's `getName()`.

See [`specs/server-authorization.md`](https://github.com/tachyonmcp/tachyon/blob/main/specs/server-authorization.md)
for the requirements, what each is proved by, and the open points.
