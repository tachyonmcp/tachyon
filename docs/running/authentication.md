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
has clients send an OAuth 2.1 access token in `Authorization: Bearer` on every request. Give Tachyon
a `BearerTokenVerifier`:

```java
var server = TachyonServer.builder()
        .security(security -> security.bearerToken(token -> {
            var jwt = jwtDecoder.decode(token); // signature, expiry, issuer, audience
            return SecurityContext.authenticated(new UserPrincipal(jwt.subject()), jwt.scopes());
        }))
        .build();
```

The verifier must check that the token was issued **for this server** (its audience, RFC 8707). The
MCP spec requires it, and it keeps tokens minted for other services out.

Throw `AuthenticationException` with `Reason.INVALID_TOKEN` for a bad token. Throw anything else
when the token cannot be checked at all, for example when an introspection endpoint is down: that
answers `500`, so the client keeps a token that may still be valid.

| Request | Response |
|---|---|
| Valid token | Continues to the handler |
| No `Authorization` header, or another scheme such as `Basic` | `401`, `WWW-Authenticate: Bearer` |
| Malformed, unknown, or expired token | `401`, `WWW-Authenticate: Bearer error="invalid_token"` |
| Several `Authorization` headers, or `?access_token=` in the URL | `400`, `WWW-Authenticate: Bearer error="invalid_request"` |
| Verifier throws anything else | `500`, no challenge |

Rejected requests have an empty body, never reach a handler, and close the connection. The token is
never logged, and a token in the query string is refused even when the header is valid.

## Custom providers

For API keys or other schemes, implement `AuthenticationProvider<HttpRequest>`. It receives the
Netty request with its headers:

```java
.security(security -> security.authenticationProvider(request -> {
    var key = request.headers().get("X-Api-Key");
    if (key == null) {
        throw new AuthenticationException(Reason.MISSING_CREDENTIALS, "No API key");
    }
    return apiKeys.lookup(key); // SecurityContext, or throw INVALID_TOKEN
}))
```

Return `SecurityContext.anonymous()` to let a request through unauthenticated.

## Reading the caller

Every handler reads the caller from its `InteractionContext`:

```java
server.tools().register(d -> d.name("whoami"), (ctx, request) -> {
    var caller = ctx.securityContext();
    if (!caller.scopes().contains("profile:read")) {
        return ToolResult.error("profile:read scope required");
    }
    return ToolResult.text(caller.principal().getName());
});
```

- `principal()` is `null` and `isAuthenticated()` is `false` for an anonymous caller.
- `scopes()` holds plain OAuth scope strings, without a `SCOPE_` prefix.
- The context never holds the token, so a handler cannot pass it on to other services by mistake.
  Call upstream APIs with their own tokens, as the MCP spec requires.
- Implement `SecurityContext` yourself to carry more verified claims, and narrow
  `ctx.securityContext()` to your type in handlers.

## How it runs

- **Per request.** Each HTTP request is authenticated on its own, including requests inside a
  2025-11-25 session. A session never stands in for credentials, as the MCP
  [security best practices](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking)
  require. Two requests on one pooled connection can carry different callers.
- **Off the event loop.** The provider runs on the handler executor, virtual threads by default, so
  it may block on introspection or key lookup. Bound those calls: the request waits for them.
- **No thread-local holder.** The context is passed along with the request. Work a handler hands to
  another thread keeps its own request's caller.
- **Sessions.** A stateful session records the security context of the request that created it.
  Handlers still see the caller of the current request.

Authentication runs after the transport checks (Origin, protocol version, `Accept`,
`Content-Type`, body size), so those errors can answer before a `401`.

## Not yet built in

- Protected Resource Metadata (RFC 9728) and `resource_metadata` in challenges, for OAuth discovery.
- `403 insufficient_scope` challenges from a scope policy.
- Binding a session to its creator, so another caller cannot use its id.
- Exposing `WWW-Authenticate` to browser scripts through CORS.

See [`specs/server-authorization.md`](https://github.com/tachyonmcp/tachyon/blob/main/specs/server-authorization.md)
for the plan.
