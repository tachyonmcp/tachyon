---
title: "Extensions"
overview_title: "Introduction"
weight: 50
sidebar_order: 50
toc: true
description: |-
  Add negotiable protocol extensions to your Tachyon server (SEP-2133), including extension-gated tool visibility.
---

Extensions add custom MCP methods. MCP 2025-11-25 clients negotiate them via the `initialize` handshake. They implement [SEP-2133](https://modelcontextprotocol.io/seps/2133-extensions).

## The ServerExtension interface

`bootstrap` receives an `ExtensionContext` (`@ExperimentalApi` — the shape may still change).
It exposes feature registries and runtime configuration without leaking transport or server
internals.

```java
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.util.Map;

public class AuditExtension implements ServerExtension {

    @Override
    public String extensionId() {
        return "com.example/audit";  // reverse-DNS format
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    @Override
    public void bootstrap(ExtensionContext context) {
        context.tools().register(
            ToolDescriptor.builder().name("audit-log").description("Writes an audit entry").build(),
            (interaction, request) -> ToolResult.text("ok"));
    }

    @Override
    public void onConnectionInit(InteractionContext ctx, ExtensionSettings clientSettings) {
        // called when a client negotiates this extension
    }

    @Override
    public ExtensionSettings serverSettings() {
        return ExtensionSettings.of(Map.of("version", "1.0"));
    }
}
```

### Raw JSON-RPC methods

For a method that doesn't fit the tool/resource/prompt/completion shape, register a raw handler
from `bootstrap`. The handler is transport-neutral: it sees the stable `InteractionContext` and a
provider-neutral `JsonObject`, never the underlying transport or server internals.

```java
@Override
public void bootstrap(ExtensionContext context) {
    context.registerHandler("com.example/audit-query", (interaction, params) -> {
        // handle the method; return value is serialized as the JSON-RPC result
        return Map.of("status", "ok");
    });
}
```

## Register an extension

```java
var server = TachyonServer.builder()
    .withExtensions(new AuditExtension())
    .port(8080)
    .build();
server.start();
```

`withExtensions` is a vararg — pass several in one call: `.withExtensions(new AuditExtension(), TasksExtension.instance())`.
Register one or more extensions with `withExtensions(...)`.

## How negotiation works

1. Tachyon advertises each registered extension's `serverSettings()` in `capabilities.extensions` — in the `initialize` response (MCP 2025-11-25) and in the `server/discover` response (MCP 2026-07-28 and later) — subject to that extension's `AdvertiseMode advertiseMode()`:

   - `ALWAYS` - Unconditionally.
   - `NEVER` - useful for internal-only extensions, e.g. `tachyon-kotlin`'s coroutine runtime (`dev.tachyonmcp/kotlin-coroutines`), that clients aren't expected to know about or negotiate directly. |
   - `NEGOTIATED` - Only if the client already declared the extension's ID in the same request — `capabilities.extensions` on `initialize`, or `_meta."io.modelcontextprotocol/clientCapabilities".extensions` on any 2026-07-28 request including `server/discover`. |

2. The client sends `initialize` with the extensions it supports, such as `"extensions": {"com.example/audit": {}}`.
3. Tachyon calls `onConnectionInit` for each extension declared by both client and server — this still works for
   `NEVER`-mode extensions if a client already knows the ID, since hiding only affects advertisement, not negotiation.
4. Methods the extension owns — declared in `methods()` or registered from `bootstrap` — are dispatched
   according to its `negotiation()` policy (below).

## Negotiation policy

`ExtensionNegotiation negotiation()` decides whether the client must declare the extension before
Tachyon dispatches the JSON-RPC methods it owns. The server checks the method exists first, so an unknown
method — or one whose extension is not installed — is always `-32601 Method not found`.

| Policy | Client declared the extension | Client did not declare it |
|---|---|---|
| `REQUIRED` (default) | dispatched | rejected, handler not invoked |
| `OPTIONAL` | dispatched | dispatched |

Declaration is read from `initialize.params.capabilities.extensions` for the MCP 2025-11-25 session, and
from `_meta."io.modelcontextprotocol/clientCapabilities".extensions` on **each** MCP 2026-07-28 request
(no carry-over between requests). A `REQUIRED` rejection is Missing Required Client Capability —
`-32021` with HTTP 400 on 2026-07-28, `-32003` on 2025-11-25:

```json
{
  "code": -32021,
  "message": "Requires the 'com.example/audit' extension",
  "data": {"requiredCapabilities": {"extensions": {"com.example/audit": {}}}}
}
```

`OPTIONAL` is a compatibility mode for clients that know an extension's wire methods but skip negotiation.
The extension is still advertised. Nothing is synthesized: `onConnectionInit` does not fire,
`InteractionContext.isExtensionEnabled` stays `false`, and extension-specific optional settings or
features are not turned on. It also skips the `requiresMetaEnvelope()` check; under `REQUIRED`, a
declared call missing `_meta.<extensionId>` is `-32602 Invalid params`.

```java
@Override
public ExtensionNegotiation negotiation() {
    return ExtensionNegotiation.OPTIONAL;
}
```

## Built-in: TasksExtension

`TasksExtension.instance()` is the reference implementation. See [Tasks](../features/tasks.md) for details.

## Built-in: SkillsExtension

`SkillsExtension` serves Agent Skills as `skill://` resources per SEP-2640. See
[extensions/mcp-skills.md](mcp-skills.md) for details.

## Extension shutdown

Override `shutdown()` to release resources when the server stops:

```java
@Override
public void shutdown() {
    scheduler.shutdown();
}
```

---

**See also:** [Tasks](../features/tasks.md) · [Tools](../features/tools.md) · [Quickstart](../quickstart.md)
