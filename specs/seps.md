# SEP index

🎯 Every `SEP-<num>` cited in code, tests or docs. A SEP is an MCP specification proposal: PR `https://github.com/modelcontextprotocol/modelcontextprotocol/pull/<num>`, text under [`docs/seps/`](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps).

One bullet per SEP, in the form spec11 reads. Tests proving a SEP carry `[SEP-<num>]` in `@DisplayName`; code implementing it cites `SEP-<num>` in a comment. New SEP cited ⇒ new bullet here.

## Transport

- **SEP-1699** MUST — A server may close an SSE stream and have the client poll by reconnecting with `Last-Event-ID`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/1699-support-sse-polling-via-server-side-disconnect.mdx)
- **SEP-2243** MUST — HTTP routing headers `Mcp-Method`, `Mcp-Name` and `Mcp-Param-*` mirror the body; a mismatch is `-32020`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2243-http-standardization.mdx)
- **SEP-2575** MUST — Stateless MCP: no `initialize` handshake; every request carries protocol version, client info and capabilities in `_meta`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2575-stateless-mcp.mdx)

## Tools

- **SEP-986** MUST — Tool names are 1 to 128 characters from a fixed set: letters, digits, `_`, `-`, `.`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/986-specify-format-for-tool-names.mdx)

## Input and elicitation

- **SEP-1034** MUST — Elicitation schemas may carry a `default` for every primitive type. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/1034--support-default-values-for-all-primitive-types-in.mdx)
- **SEP-1330** MUST — Elicitation enums: titled and untitled, single-select and multi-select schemas. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/1330-elicitation-enum-schema-improvements-and-standards.mdx)
- **SEP-2322** MUST — Multi round-trip requests: a server answers `input_required` and the client retries with `inputResponses`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2322-MRTR.mdx)

## Caching

- **SEP-2549** MUST — Cacheable results (lists, `skills/get`) carry `ttlMs` and `cacheScope` so clients know how long and how widely to cache them. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2549-TTL-for-list-results.mdx)

## Tasks

- **SEP-1686** MUST — Tasks for MCP 2025-11-25: task-augmented requests, `tasks/get`, `tasks/result`, `tasks/list`, `tasks/cancel`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/1686-tasks.mdx)
- **SEP-2663** MUST — Tasks extension for MCP 2026-07-28: a tool call returns a task, driven by `tasks/get`, `tasks/update`, `tasks/cancel`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2663-tasks-extension.mdx)

## Extensions

- **SEP-2133** MUST — Extensions: namespaced optional capabilities, declared and negotiated under `capabilities.extensions`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2133-extensions.mdx)
- **SEP-1865** MUST — MCP Apps: tools hand the host interactive HTML views served as `ui://` resources. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp.mdx)
- **SEP-2640** MUST — Skills extension: `skills/list`, `skills/get` and `skill://` resources; both results carry `ttlMs` and `cacheScope`. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2640-skills-extension.mdx)

## Deprecations

- **SEP-2577** MUST — Deprecates roots, sampling and logging. [Text](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/seps/2577-deprecate-roots-sampling-and-logging.mdx)
