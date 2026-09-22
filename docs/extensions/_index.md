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
| [Tasks](../features/tasks.md) | `io.modelcontextprotocol/tasks` | `tachyon-core` | `.capabilities(c -> c.tasks(connector))`; registers the extension automatically |
| [Skills](mcp-skills.md) | `io.modelcontextprotocol/skills` | `tachyon-extensions-skills` | `.withExtensions(SkillsExtension.builder()...build())` |

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
| Declaration lasts | The whole session; requires server sessions | One request; repeat it on every request |
| Undeclared call to a `REQUIRED` extension method | JSON-RPC error `-32003` (Tachyon-defined) | JSON-RPC error `-32021`, HTTP `400` |

On MCP 2025-11-25 the declaration is stored on the session. Tachyon servers are stateless by
default, so enable sessions with `.session(session -> session.enabled())` if 2025-11-25 clients use
`REQUIRED` extensions. Without a session, the declaration is lost after `initialize` and every call
to the extension's methods is rejected with `-32003`.

The 2025-11-25 schema defines no `extensions` capability. Tachyon follows
[SEP-2133](https://modelcontextprotocol.io/seps/2133-extensions) there, which negotiates extensions
through `initialize` capabilities. MCP 2026-07-28 adds `extensions` to the schema and moves client
declarations into each request.

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
features are not turned on.

Built-in extensions expose the policy on their builders; see
[Skills negotiation](mcp-skills.md#extension-negotiation).

## Write your own extension

To add your own methods or extension-gated features, follow [Write an extension](custom-extensions.md).
It builds a working extension step by step and covers the authoring API.
