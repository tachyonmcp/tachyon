---
title: "Extensions"
overview_title: "Introduction"
weight: 50
sidebar_order: 50
toc: true
description: |-
  Use protocol extensions in your Tachyon server (SEP-2133): built-in extensions, client negotiation per MCP version, and negotiation policy.
---

Extensions add optional MCP features beyond the base protocol, such as tasks or skills. The server
advertises the extensions it supports; a client declares the ones it uses. To build your own, see
[Write an extension](custom-extensions.md). Tachyon implements
[SEP-2133](https://modelcontextprotocol.io/seps/2133-extensions) negotiation for MCP 2025-11-25 and
2026-07-28.

## Available extensions

| Extension | ID | Module | Enable with |
|---|---|---|---|
| [Tasks](tasks.md) | `io.modelcontextprotocol/tasks` | `tachyon-extensions-tasks` | `.withExtension(TasksExtension.class, t -> t.connector(connector))` |
| [Skills](skills.md) | `io.modelcontextprotocol/skills` | `tachyon-extensions-skills` | `.withExtension(SkillsExtension.class, s -> s.registry(registry))` |

## Add an extension

Add the extension's module. `tachyon-bom` manages its version:

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-extensions-skills</artifactId>
</dependency>
```

Then register it on the server builder by class and configure its builder:

```java
var server = TachyonServer.builder()
        .withExtension(SkillsExtension.class, skills -> skills
                .registry(new FilesystemSkillsRegistry(Path.of("skills"))))
        .port(8080)
        .build();
server.start();
```

Configurable extensions ([Tasks](tasks.md), [Skills](skills.md)) are registered by class with
`withExtension(Type.class, …)`; `withExtensions` takes ready-made instances of extensions that need no
configuration and rejects configurable ones. Kotlin: `withExtension(SkillsExtension::class.java) {
registry(registry) }`, `tasks(connector) { }` for Tasks, `extensions(...)` for instances.

## What clients must send

A client uses an extension by declaring its ID. Where it declares it, and what it gets back
when it doesn't, depend on the MCP version:

| | MCP 2025-11-25 | MCP 2026-07-28 |
|---|---|---|
| Server advertises extensions in | `initialize` result, `capabilities.extensions` | `server/discover` result, `capabilities.extensions` |
| Client declares extensions in | `initialize` params, `capabilities.extensions` | `_meta."io.modelcontextprotocol/clientCapabilities".extensions` |
| Declaration lasts | The whole session; without sessions, only the `initialize` request | One request; repeat it on every request |
| Undeclared call to a `REQUIRED` extension method | JSON-RPC error `-32003` (Tachyon-defined) | JSON-RPC error `-32021`, HTTP `400` |

On MCP 2025-11-25 the declaration is stored on the session. Tachyon servers are stateless by
default. Without a session, every HTTP POST has a fresh context: later requests cannot
see what the client declared in `initialize`, even on the same TCP connection. With the default `OPTIONAL` policy the extension's
methods still work, but `isExtensionEnabled` reports `false`. If 2025-11-25 clients use a
`REQUIRED` extension, enable sessions with `.session(session -> session.enabled())`; otherwise its
methods are rejected with `-32003`, and the server logs a warning at startup.

The 2025-11-25 schema defines no `extensions` capability. Tachyon follows
[SEP-2133](https://modelcontextprotocol.io/seps/2133-extensions) there, which negotiates extensions
through `initialize` capabilities. MCP 2026-07-28 adds `extensions` to the schema and moves client
declarations into each request, as described in the
[extension negotiation overview](https://modelcontextprotocol.io/extensions/overview#negotiation).
That per-request capability mechanism does not apply to MCP 2025-11-25.

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
| `OPTIONAL` (default) | dispatched | dispatched |
| `REQUIRED` | dispatched | rejected, handler not invoked |

`OPTIONAL` follows the SEP-2133 fallback rule: the server dispatches the extension-owned handler even
when the client did not declare the extension. The handler checks `isExtensionEnabled` and chooses
any core fallback; the server does not choose one for it. `OPTIONAL` controls dispatch only.
Advertisement follows `AdvertiseMode`: `ALWAYS` is always advertised, `NEGOTIATED` is advertised only
when enabled, and `NEVER` is never advertised. For an undeclared client, `onConnectionInit` does not
fire, `InteractionContext.isExtensionEnabled` stays `false`, and extension-specific settings or
features are not turned on. An undeclared `NEGOTIATED` extension is neither enabled nor advertised.

Choose `REQUIRED` only for a mandatory extension, one whose methods can't work for a client that
doesn't support it. On MCP 2025-11-25 it needs server sessions.

Built-in extensions expose the policy on their builders; see
[Skills negotiation](skills.md#extension-negotiation).

## Write your own extension

To add your own methods or extension-gated features, follow [Write an extension](custom-extensions.md).
It builds a working extension step by step and covers the authoring API.
