# Liveness endpoint

🎯 An orchestrator (Kubernetes, a load balancer) learns the process still answers HTTP. Issue [#474](https://github.com/tachyonmcp/tachyon/issues/474).

- **Off by default.** `NetworkConfig.Builder#liveness()` serves `/livez`; `#liveness(String path)` serves `path`. Same path rules as `endpointPath`; must differ from it.
- **`GET`/`HEAD`** on the path (query string and trailing `/` ignored) → `200`, `Cache-Control: no-store`.
  - `Accept` admits `application/json` → `application/json`, body `{"status":"UP"}`.
  - Otherwise → `text/plain; charset=UTF-8`, body `UP`.
  - `HEAD` → the `GET` headers, `Content-Length` included, no body.
- **Other methods** → `405`, `Allow: GET, HEAD`, connection closes.
- **Declared body** (`Content-Length` ≠ 0, chunked) → answered, connection closes without reading the body. The probe sits ahead of the aggregator's size limit; an unfinished body must not stream on unauthenticated.
- **No guards.** No authentication, no `Host`/`Origin` check, no CORS. A probe carries no token and sends `Host: <podIP>:<port>`; the answer carries no data, so the guards protect nothing here.
- **Keep-alive** honored; pipelined requests stay ordered.
- **Disabled** → the path is `404`, as any unknown path.

⚠️ Liveness means the event loop answers. No MCP ping, no dispatcher or registry check. Readiness (`/readyz`) is out of scope.

✅ Acceptance (E2E): each bullet above, plus a probe with a foreign `Host` and a probe to a bearer-protected server both get `200`.
