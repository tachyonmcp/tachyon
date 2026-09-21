---
title: "Documentation"
sidebar_title: "Documentation"
weight: 1
sidebar_order: 1
toc: true
description: |-
  Run your first Tachyon MCP server, build tools, and prepare for deployment.
---

Start with a running server, add the features your application needs, then prepare it for production.

## Start

- [Run your first server](quickstart.md) — build a Java server with Maven or Gradle and call its greeting tool with curl.
- [Use Spring Boot](spring-boot/) — build a greeting tool from a Spring bean and call it with MCP Inspector.
- [Explore the Kotlin DSL](kotlin/) — start a Kotlin server, configure it, and write suspend handlers.
- [Test a tool](testkit.md) — exercise a running server through an MCP client.

## Build

- [Tools](features/tools.md) — define inputs, return results, and handle errors.
- [Resources](features/resources.md) and [prompts](features/prompts.md) — expose application data and reusable messages.
- [Annotations](annotations.md) — declare feature methods and bind typed arguments.
- [Completions](features/completions.md) — suggest prompt arguments and resource variables.
- [JSON schemas](json.md) — customize validation and serialization.
- [Tasks](features/tasks.md) and [client interactions](features/client-interactions.md) — support longer work and request user input.

## Operate

- [Configuration](running/configuration.md) — set network, runtime, and server options.
- [Deployment](running/deployment.md) — bind your server and configure its public hostname.
- [HTTP protection](running/configuration.md#dns-rebinding-protection) — configure accepted hosts and request guards.
- [Observability](running/observability.md) — observe calls and integrate tracing.
- [Shutdown](faq.md#does-shutdown-wait-for-active-handlers) — allow in-flight handlers to finish when stopping the server.
