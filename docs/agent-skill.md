---
title: "Agent Skill"
sidebar_title: "Build with an agent"
weight: 12
sidebar_order: 12
toc: true
description: |-
  Install the tachyon-mcp agent skill so Claude Code, Codex, Cursor, and other coding agents write correct Tachyon servers.
---

The `tachyon-mcp` [agent skill](https://github.com/tachyonmcp/tachyon/tree/main/.agents/skills/tachyon-mcp)
teaches coding agents the Tachyon API. With it installed, an agent can scaffold a server, add
tools, resources, and prompts, and configure the runtime without guessing method names.

## Install

Run the [skills CLI](https://github.com/vercel-labs/skills) from your project root:

```bash
npx skills add tachyonmcp/tachyon --skill tachyon-mcp
```

The CLI asks which agents to install the skill for. Common options:

| Option | Effect |
|---|---|
| `-g` | Install for your user instead of the current project |
| `-a claude-code` | Install for one agent; use `'*'` for all detected agents |
| `-y` | Skip confirmation prompts, for scripts and CI |

Update it after upgrading Tachyon. The CLI pulls the latest skill from the
[`tachyonmcp/tachyon`](https://github.com/tachyonmcp/tachyon) GitHub repository:

```bash
npx skills update tachyon-mcp
```

## Use

Agents load the skill when a task mentions Tachyon or MCP servers. Name the skill to make the
choice explicit:

```markdown
Use the **tachyon-mcp** skill. Add a get_forecast tool to MyMcpServer that takes a city
and returns a structured forecast. Reject an empty city with a tool error.
```

Good first tasks:

- Create a server from scratch, as in the [Quickstart](quickstart.md).
- Add a tool, resource, or prompt to an existing server.
- Port a handler to the [Kotlin DSL](kotlin/).
- Configure CORS, sessions, or timeouts with [configuration](running/configuration.md).

Review generated code and run it as in [Quickstart step 3](quickstart.md#3-call-the-greeting-tool)
or with the [testkit](testkit.md).

## What it covers

- Dependencies through `tachyon-bom`, with `tachyon-core` or `tachyon-kotlin`.
- `ServerBuilder` methods, server lifecycle, and registration before or after `start()`.
- Tool, resource, and prompt handlers, plus virtual-thread rules for blocking code.
- Capabilities, network, session, runtime, and monitoring configuration.
- JSON Schema, extensions, tests, and the Kotlin DSL.

The skill's Java and Kotlin examples compile in the Tachyon build, so they track the released API.

> [!NOTE]
> This skill helps agents write server code. It is unrelated to [`SkillsExtension`](extensions/skills.md),
> which serves skills from your MCP server to MCP clients.
