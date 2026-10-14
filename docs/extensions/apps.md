---
title: "MCP Apps"
weight: 15
sidebar_order: 15
toc: true
description: |-
  Ship interactive HTML views (SEP-1865 MCP Apps) from your Tachyon server: ui:// resources, tool linkage, CSP and permissions.
---

Text answers only go so far. [MCP Apps][SEP-1865] let a tool hand the host an interactive HTML
view (a chart, a form, a viewer) that renders inside the conversation in a sandboxed iframe. The
server side is small and declarative: a `ui://` resource with MIME type `text/html;profile=mcp-app`,
and a tool whose `_meta.ui.resourceUri` points at it. Everything else (the `postMessage` JSON-RPC
dialect, theming, display modes) happens between the host and the HTML.

Tachyon's `AppsExtension` (`tachyon-extensions-apps`) implements the server half of [SEP-1865][SEP-1865]:
it serves views as `ui://` resources with `_meta.ui` on both the listing and the content, builds the
tool metadata, and advertises `io.modelcontextprotocol/ui`.

## Enable the extension

This server serves one view from the classpath and links a tool to it
([source](https://github.com/tachyonmcp/tachyon/blob/main/examples/mcp-apps/src/main/java/com/example/apps/DiceServer.java)):

<!-- snips: ../../examples/mcp-apps/src/main/java/com/example/apps/DiceServer.java#apps_enable -->
```java
var server = TachyonServer.builder()
    .host(host)
    .port(port)
    .network(n -> {
        if (allowedHost != null && !allowedHost.isBlank()) {
            n.allowedHosts(allowedHost);
        }
    })
    .info(info -> info.name("dice").title("Dice").version("1.0"))
    .withExtension(AppsExtension.class, apps -> apps
        .app(res->res
            .uri(VIEW_URI)                                   // ui:// scheme is mandatory
            .name("dice_roll")
            .title("Dice roll")
            .description("Shows the rolled dice as pips")
            .html(() -> classpath("apps/dice.html"))         // one self-contained HTML document
            .meta(AppMeta.builder().prefersBorder(true).build())
            .build()))
    .withTools(tools -> tools.register(
        tool -> tool.name("roll_dice")
            .description("Roll one or more six-sided dice")
            .inputSchema("""
                {"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":10}}}
                """)
            .meta(AppsExtension.toolMeta(VIEW_URI)),          // host renders the result in the view
        (context, request) -> {
            var count = request.arguments().intOr("count", 2);
            var rolls = ThreadLocalRandom.current().ints(count, 1, 7).boxed().toList();
            return ToolResult.structured(  // structuredContent for the view...
                Map.of("rolls", rolls),
                "Rolled " + rolls); // ...text for hosts without apps
        }))
    .build();
```

With `tachyon-kotlin`, add `dev.tachyonmcp:tachyon-extensions-apps` explicitly (the dependency is
optional) and use the receiver adapter:

```kotlin
import dev.tachyonmcp.extensions.apps.AppMeta
import dev.tachyonmcp.extensions.apps.AppsExtension
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.config.apps

fun main() {
    TachyonServer(port = 8080) {
        apps {
            app(uri = "ui://dice/roll", name = "dice_roll", meta = AppMeta.builder().prefersBorder(true).build()) {
                readClasspath("apps/dice.html")
            }
        }
        tool(name = "roll_dice", meta = AppsExtension.toolMeta("ui://dice/roll")) { text("Rolled [4, 2]") }
    }
}
```

The full runnable example, including the HTML view, lives in
[`examples/mcp-apps`](https://github.com/tachyonmcp/tachyon/tree/main/examples/mcp-apps).

## The view

A view is one HTML5 document. The host fetches it with `resources/read` and renders the `text` in
a sandboxed iframe under a restrictive Content Security Policy, with no base URL: inline your
scripts and styles, or declare their origins in `AppMeta`. `AppResource.html` takes a `Supplier`,
so you can read the document from the classpath, a file, or a template on every read.

| Rule ([SEP-1865][SEP-1865]) | Enforced by |
|---|---|
| URI MUST start with `ui://` | `AppResource` and `AppsExtension.toolMeta` throw `IllegalArgumentException` |
| `mimeType` MUST be `text/html;profile=mcp-app` | always set; `AppsExtension.MIME_TYPE` |
| Content via `text` | `TextResourceContents` |
| `_meta.ui` on the listing entry and/or the content item (content wins) | the same `AppMeta` is emitted in both places |

`AppMeta` is the `_meta.ui` object. Every field is optional and omitted when unset, which is the
spec's secure default:

| Builder | Wire | Effect |
|---|---|---|
| `connectDomains(...)` | `csp.connectDomains` | `fetch`/XHR/WebSocket origins (`connect-src`) |
| `resourceDomains(...)` | `csp.resourceDomains` | scripts, styles, images, fonts, media; `https://*.example.com` allowed |
| `frameDomains(...)` | `csp.frameDomains` | nested iframes (`frame-src`), else `'none'` |
| `baseUriDomains(...)` | `csp.baseUriDomains` | `base-uri`, else `'self'` |
| `permissions(CAMERA, MICROPHONE, GEOLOCATION, CLIPBOARD_WRITE)` | `permissions.{camera,...}: {}` | iframe `allow` features; hosts may refuse, feature-detect |
| `domain("...")` | `domain` | dedicated sandbox origin, format is host-specific |
| `prefersBorder(true/false)` | `prefersBorder` | visible border and background; declare it, host defaults vary |

`AppMeta.NONE` declares nothing; the resource then carries no `_meta` at all.

## Linking tools to views

`AppsExtension.toolMeta(uri, visibility...)` builds the tool's `_meta`:

```json
{"ui": {"resourceUri": "ui://dice/roll", "visibility": ["model", "app"]}}
```

Pass it to `ToolDescriptor.Builder#meta` (Java) or `tool(name, meta = ...)` (Kotlin). Without
`visibility` the key is omitted and hosts apply the spec default, `["model", "app"]`:

| `UiToolVisibility` | Who calls the tool |
|---|---|
| `MODEL` | the agent; the tool appears in the model's tool list |
| `APP` | the view, over the same server connection only; `APP` alone hides the tool from the model |

The deprecated flat `_meta["ui/resourceUri"]` key is not emitted; the spec removes it before GA.

The referenced resource MUST exist on the server: register it with the extension, or the host's
`resources/read` fails. Tools MUST still return meaningful `content`: a host without MCP Apps
ignores `_meta.ui` and shows the text. Put the data the view needs in `structuredContent`.

## Negotiation

Hosts declare `io.modelcontextprotocol/ui` with the MIME types they render:

```json
{"capabilities": {"extensions": {"io.modelcontextprotocol/ui": {"mimeTypes": ["text/html;profile=mcp-app"]}}}}
```

`AppsExtension` uses `AdvertiseMode.ALWAYS` and adds no JSON-RPC methods, so there is nothing to
gate: views are plain resources, readable by every client, and tool metadata degrades to text on
hosts without MCP Apps. See [Extensions](_index.md#what-clients-must-send) for where each MCP
revision carries the declaration.

## Caveats

- **`text` only.** `blob` content and per-read `_meta` overrides are not supported yet.
- **No cross-check at startup.** The extension does not verify that every tool's `resourceUri`
  resolves to a registered view; tools may register after the extension bootstraps.
- **`@ExperimentalApi`.** The package (`dev.tachyonmcp.extensions.apps`, module
  `tachyon-extensions-apps`) is marked experimental; the shape may change while [SEP-1865][SEP-1865]
  is a draft.

[SEP-1865]: https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/draft/apps.mdx "SEP-1865: MCP Apps"
