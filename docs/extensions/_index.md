---
title: "Extensions"
overview_title: "Introduction"
weight: 50
sidebar_order: 50
toc: true
description: |-
  Add protocol extensions to your Tachyon server (SEP-2133): built-in extensions, client negotiation per MCP version, and writing your own extension.
---

Extensions add optional MCP features beyond the base protocol, such as tasks or skills. The server
advertises the extensions it supports; a client declares the ones it uses. Tachyon implements
[SEP-2133](https://modelcontextprotocol.io/seps/2133-extensions) negotiation for MCP 2025-11-25 and
2026-07-28.

## Available extensions

| Extension | ID | Module | Enable with |
|---|---|---|---|
| [Tasks](../features/tasks.md) | `io.modelcontextprotocol/tasks` | `tachyon-core` | `.capabilities(c -> c.tasks(connector))`; registers the extension automatically |
| [Skills](mcp-skills.md) | `io.modelcontextprotocol/skills` | `tachyon-extensions-skills` | `.withExtensions(SkillsExtension.builder()...build())` |
| Kotlin coroutine runtime | `dev.tachyonmcp/kotlin-coroutines` | `tachyon-kotlin` | Added by the Kotlin DSL; never advertised to clients |

## Add an extension

Add the extension's module. `tachyon-bom` manages its version:

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-extensions-skills</artifactId>
</dependency>
```

Then register it on the server builder. `withExtensions` takes several extensions at once:

```java
var server = TachyonServer.builder()
        .withExtensions(SkillsExtension.builder()
                .registry(new FilesystemSkillsRegistry(Path.of("skills")))
                .build())
        .port(8080)
        .build();
server.start();
```

In the Kotlin DSL, use `extensions(...)` on the builder. Tasks need no `withExtensions` call;
configuring a task connector enables them.

## What clients must send

A client uses an extension by declaring its ID. Where it declares it, and what it gets back
when it doesn't, depend on the MCP version:

| | MCP 2025-11-25 | MCP 2026-07-28 |
|---|---|---|
| Server advertises extensions in | `initialize` result, `capabilities.extensions` | `server/discover` result, `capabilities.extensions` |
| Client declares extensions in | `initialize` params, `capabilities.extensions` | `_meta."io.modelcontextprotocol/clientCapabilities".extensions` |
| Declaration lasts | The whole session | One request; repeat it on every request |
| Undeclared call to a `REQUIRED` extension method | JSON-RPC error `-32003` | JSON-RPC error `-32021`, HTTP `400` |

A client declares an extension with its ID as a key, for example
`"extensions": {"io.modelcontextprotocol/skills": {}}`. The value carries the client's settings for
that extension.

The rejection is Missing Required Client Capability. Its `data` names the extension to declare:

```json
{
  "code": -32021,
  "message": "Requires the 'com.example/audit' extension",
  "data": {"requiredCapabilities": {"extensions": {"com.example/audit": {}}}}
}
```

A method that does not exist, including one whose extension is not installed, is always
`-32601 Method not found` (HTTP `404` on MCP 2026-07-28).

## Negotiation policy

`ExtensionNegotiation negotiation()` decides whether the client must declare the extension before
Tachyon dispatches the JSON-RPC methods it owns:

| Policy | Client declared the extension | Client did not declare it |
|---|---|---|
| `REQUIRED` (default) | dispatched | rejected, handler not invoked |
| `OPTIONAL` | dispatched | dispatched |

`OPTIONAL` is a compatibility mode for clients that call an extension's methods without declaring it.
The extension is still advertised. Nothing is synthesized: `onConnectionInit` does not fire,
`InteractionContext.isExtensionEnabled` stays `false`, and extension-specific optional settings or
features are not turned on. It also skips the `requiresMetaEnvelope()` check; under `REQUIRED`, a
declared call missing `_meta.<extensionId>` is `-32602 Invalid params`.

Built-in extensions expose the policy on their builders; see
[Skills negotiation](mcp-skills.md#extension-negotiation).

## Write an extension

> **API status:** `ServerExtension` and `ExtensionContext` are `@ExperimentalApi`; their shape may
> still change.

Implement `ServerExtension`. `bootstrap` runs once at server startup and receives an
`ExtensionContext` with the feature registries and runtime configuration, but no transport or
server internals:

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
        return "com.example/audit";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    @Override
    public ExtensionSettings serverSettings() {
        return ExtensionSettings.of(Map.of("version", "1.0"));
    }

    @Override
    public void bootstrap(ExtensionContext context) {
        context.tools().register(
                ToolDescriptor.builder()
                        .name("audit-log")
                        .description("Writes an audit entry")
                        .extensionId(extensionId())
                        .build(),
                (interaction, request) -> ToolResult.text("ok"));
    }

    @Override
    public void onConnectionInit(InteractionContext ctx, ExtensionSettings clientSettings) {
        // the client declared this extension
    }
}
```

Use a reverse-DNS extension ID. Register the extension with `withExtensions(new AuditExtension())`.

### Extension-gated features

A tool, resource, or prompt whose descriptor sets `extensionId(...)` belongs to that extension.
Clients that did not declare the extension don't see it in `tools/list`, `resources/list`, or
`prompts/list`, and calling it fails as unknown. Without `extensionId`, a feature registered from
`bootstrap` is visible to every client.

### Raw JSON-RPC methods

For a method that doesn't fit the tool, resource, prompt, or completion shape, register a raw handler
from `bootstrap`. The handler sees the `InteractionContext` and a provider-neutral `JsonObject`,
never the transport. The extension's negotiation policy gates the method:

```java
@Override
public void bootstrap(ExtensionContext context) {
    context.registerHandler("com.example/audit-query", (interaction, params) -> {
        return Map.of("status", "ok");
    });
}
```

The return value becomes the JSON-RPC result. By default, `requiresMetaEnvelope()` is `true`: a
declared call must also carry the extension's key in its params, such as
`"_meta": {"com.example/audit": {}}`, or it fails with `-32602 Invalid params`. Override
`requiresMetaEnvelope()` to return `false` when your methods take no envelope.

### Advertisement

`AdvertiseMode advertiseMode()` controls whether `capabilities.extensions` lists the extension and its
`serverSettings()`:

| Mode | Advertised |
|---|---|
| `ALWAYS` | Always |
| `NEGOTIATED` | Only when the client declared the extension in the same request |
| `NEVER` | Never; for internal extensions such as the Kotlin coroutine runtime |

Advertisement does not affect negotiation: a client that already knows a `NEVER` extension's ID can
still declare it.

### Lifecycle

| Callback | When it runs |
|---|---|
| `bootstrap(ExtensionContext)` | Once, at server startup |
| `onConnectionInit(InteractionContext, ExtensionSettings)` | When a client declares the extension: at `initialize` on MCP 2025-11-25, on each declaring request on MCP 2026-07-28 |
| `shutdown()` | When the server stops; release resources here |

```java
@Override
public void shutdown() {
    scheduler.shutdown();
}
```

---

**See also:** [Tasks](../features/tasks.md) · [Skills](mcp-skills.md) · [Tools](../features/tools.md) · [Quickstart](../quickstart.md)
