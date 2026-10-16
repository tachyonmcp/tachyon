# Streamable HTTP authorization: validation

Acceptance for [requirements.md](requirements.md) (IDs such as K10 are its rows); design in [plan.md](plan.md).

E2E, port 0, both protocol versions unless marked. ✅ = proven by the named test.

- **Unconfigured:** every existing E2E passes unchanged; `tasks.list` is still declared.
- ✅ **Discovery:** request without a token ⇒ `401` + `WWW-Authenticate: Bearer resource_metadata="…", scope="…"`. Both well-known URIs serve PRM without a token. With `resource = https://api.example.com/mcp` and a plain-HTTP request whose `Host` / `X-Forwarded-Host` is spoofed, `resource` and `resource_metadata` still name `https://api.example.com` (`DiscoveryTest`, 2025 and 2026).
- **Tokens:** expired, wrong audience, or bad signature ⇒ `401 invalid_token`. `?access_token=` ⇒ `400`. No log line at INFO or DEBUG contains the token: the DEBUG wire log carries sizes only (`WireLogTest`). The TRACE wire log carries the bytes, so it stays off in production. Handlers cannot read the raw token.
- **Head authentication (K10):** no token + `Expect: 100-continue` + a body declared above `maxContentLength` ⇒ `401`, no `100 Continue`, no `413`, connection closed. An invalid protocol version without a token ⇒ `401`, not a version error; with a token ⇒ `400` as before. A preflight without a token ⇒ the normal CORS answer. A refusal carries the CORS headers. A verifier that never answers ⇒ `503` + `Retry-After`, no challenge, within the configured timeout. A valid token with a body of 512 KB, with and without `Expect`, over a slow verifier ⇒ success with the body intact (`HeadAuthenticationTest`).
- ✅ **Scopes (`tools/call`):** missing scope ⇒ `403 insufficient_scope` with every required scope in one challenge, before any SSE bytes. The policy sees the body's tool name, not a forged `Mcp-Name` (`RequiredScopesTest`, 2025 and 2026).
- **2025 sessions:** session created by A, a POST/GET/DELETE from B or anonymous ⇒ `404`; A unaffected. An anonymous session rejects an authenticated caller. `Last-Event-ID` from B ⇒ `404`, nothing replayed. A restored session keeps its owner key.
- **2026 listen:** B's listen never receives A's task status. ✅ The listen stream closes at token `expiresAt` and a fresh token listens again (`TokenLifetimeTest`).
- **Tasks:** same principal, two sessions (2025) ⇒ `tasks/get` succeeds from both. A different principal ⇒ `Task not found`, connector not called. An anonymous task on a stateful 2025 server ⇒ only the creating session reaches it (#393 unchanged). `ctx.tasks().publish` from a plain tool ⇒ `server.tasks().get(id)` is non-null. A job publishing before its task tool returns ⇒ the call succeeds with the same owner. Anonymous publish, then an owned `create` ⇒ refused.
