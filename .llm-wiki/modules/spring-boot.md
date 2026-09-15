---
title: Spring Boot starter
tags: [module, spring-boot, configuration]
sources: [pom.xml, integrations/tachyon-spring-boot-starter/]
updated: 2026-09-15
commit: 751331f4
---

# 🌱 Spring Boot starter

Build → register annotated singletons → start transport. Native annotation contracts live in [[declarative-configuration]].

- `tachyon.*` → `TachyonProperties` record (enabled=true, name, version, host, port=8080) [TachyonProperties.java:21](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonProperties.java:21).
- Builds server with properties, ordered extensions and customizers, then registers annotated beans after singleton initialization, before lifecycle startup ([TachyonAutoConfiguration.java:48](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java:48), [TachyonBeanRegistrar.java:20](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrar.java:20)). Annotated beans can inject `TachyonServer`.
- Discovery uses `AopUtils.getTargetClass` on initialized beans; JDK and class proxies retain advice. Unrelated lazy beans stay uninitialized; lazy annotated beans need discoverable declared types ([TachyonBeanRegistrar.java:21](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrar.java:21)).
- User server ⇒ construction and registrar back off together; lifecycle still supplied ([TachyonAutoConfiguration.java:31](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java:31), [TachyonAutoConfiguration.java:69](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java:69)).
- Wire regression tests cover all three feature kinds through JDK proxy advice, server injection, custom codecs, lazy-bean isolation and user-server backoff ([TachyonBeanRegistrationTest.java:150](../../integrations/tachyon-spring-boot-starter/src/test/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrationTest.java:150)).
- Spring Boot `4.1.1` pinned in the root [pom.xml:91](../../pom.xml:91); starter dependencies use that property, with no Boot BOM import in the [module POM:22](../../integrations/tachyon-spring-boot-starter/pom.xml:22).

- Completion-only beans retain target parameter names and proxy advice ([TachyonCompletionBeanTest.java:61](../../integrations/tachyon-spring-boot-starter/src/test/java/dev/tachyonmcp/spring/boot/TachyonCompletionBeanTest.java:61)).
- `TachyonServerLifecycle` starts/closes the server with the application context ([TachyonServerLifecycle.java:29](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonServerLifecycle.java:29)).

Related: [[configuration]], [[extensions]], [[integrations]].
