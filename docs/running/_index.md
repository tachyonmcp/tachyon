---
title: "Running Tachyon"
overview_title: "Plan a deployment"
weight: 40
sidebar_order: 40
toc: true
description: |-
  Configure, deploy, and observe a Tachyon MCP server.
---

Start with configuration while developing locally. Read the deployment guide before exposing the
server outside loopback, then add observability for the signals your environment collects.

## Configure and run

- [Configuration](configuration/) — network, sessions, capabilities, runtime, and JSON settings
- [Deployment](deployment/) — bind addresses, public hosts, browser clients, and multiple instances
- [Observability](observability/) — OpenTelemetry spans, metrics, and payload capture controls

Tachyon is stateless by default. Enable sessions only when you need resumable streams or
session-scoped state; multi-instance deployments then require sticky routing for active sessions.
