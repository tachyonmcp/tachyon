# MCP Apps Example

Rolls dice and renders the result as an interactive view inside the chat, using Tachyon's
`AppsExtension` ([SEP-1865 MCP Apps](https://modelcontextprotocol.io/extensions/apps/overview)).

- `ui://dice/roll` is served from `src/main/resources/apps/dice.html`: one self-contained HTML
  document with MIME type `text/html;profile=mcp-app`.
- The `roll_dice` tool carries `_meta.ui.resourceUri = "ui://dice/roll"`. A host that supports MCP
  Apps renders the tool result in the view; every other host shows the tool's text.
- The view speaks the SEP-1865 postMessage protocol directly (`ui/initialize`,
  `ui/notifications/initialized`, `ui/notifications/tool-result`), no SDK needed.

> Requires Tachyon `1.1.0` or a `1.1.0-SNAPSHOT` built from this repository
> (`./mvnw -q install -DskipTests` at the repository root).

## Quickstart

```shell
./mvnw package
java -jar target/mcp-apps-example.jar
```

Connect a host that supports MCP Apps (Claude Desktop, VS Code, MCP Inspector) to
`http://localhost:8080/mcp` and ask it to roll some dice.

## Wire format

`resources/read` for `ui://dice/roll`:

```json
{
  "contents": [{
    "uri": "ui://dice/roll",
    "mimeType": "text/html;profile=mcp-app",
    "text": "<!DOCTYPE html>...",
    "_meta": {"ui": {"prefersBorder": true}}
  }]
}
```

`tools/list` entry:

```json
{
  "name": "roll_dice",
  "description": "Roll one or more six-sided dice",
  "inputSchema": {"type": "object", "properties": {"count": {"type": "integer", "minimum": 1, "maximum": 10}}},
  "_meta": {"ui": {"resourceUri": "ui://dice/roll"}}
}
```
