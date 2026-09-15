---
title: Spring Boot starter
tags: [module, spring-boot, configuration]
sources: [pom.xml, integrations/tachyon-spring-boot-starter/]
updated: 2026-09-15
commit: 751331f4
---

# 🌱 Spring Boot starter

Build → register annotated singletons → start transport. Native annotation contracts live in [[declarative-configuration]].

- `tachyon.*` → `TachyonProperties` record (enabled=true, name, version, host, port=8080) [TachyonProperties](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonProperties.java).
- Builds server with properties, ordered extensions and customizers, then registers annotated beans after singleton initialization, before lifecycle startup ([ServerConfiguration#tachyonServer](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java), [TachyonBeanRegistrar#afterSingletonsInstantiated](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrar.java)). Annotated beans can inject `TachyonServer`.
- Discovery uses `AopUtils.getTargetClass` on initialized beans; JDK and class proxies retain advice. Unrelated lazy beans stay uninitialized; lazy annotated beans need discoverable declared types ([TachyonBeanRegistrar#afterSingletonsInstantiated](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrar.java)).
- User server ⇒ construction and registrar back off together; lifecycle still supplied ([TachyonAutoConfiguration#TachyonAutoConfiguration](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java), [ServerConfiguration](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java)).
- Wire regression tests cover all three feature kinds through JDK proxy advice, server injection, custom codecs, lazy-bean isolation and user-server backoff ([TachyonBeanRegistrationTest#jdkProxyUsesTargetAnnotationsAndPreservesAdviceForEveryFeature](../../integrations/tachyon-spring-boot-starter/src/test/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrationTest.java)).
- Spring Boot `4.1.1` pinned in the root [pom.xml](../../pom.xml); starter dependencies use that property, with no Boot BOM import in the [module POM](../../integrations/tachyon-spring-boot-starter/pom.xml).

- Completion-only beans retain target parameter names and proxy advice ([TachyonCompletionBeanTest#discoversCompletionOnlyProxyAndBindsTargetParameterNamesThroughAdvice](../../integrations/tachyon-spring-boot-starter/src/test/java/dev/tachyonmcp/spring/boot/TachyonCompletionBeanTest.java)).
- `TachyonServerLifecycle` starts/closes the server with the application context; lock serializes calls, `running` flips only after `start`/`close` succeeds (failure keeps prior state, retry allowed) ([TachyonServerLifecycle#start](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonServerLifecycle.java)).
- Actuator (all optional): `HealthConfiguration` needs `HealthIndicator` class, then `management.health.tachyon.enabled` ⇒ `tachyon` indicator, `UP` + host/port iff lifecycle running ([TachyonHealthIndicator#health](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonHealthIndicator.java)). `MetricsConfiguration` needs Micrometer class + `MeterRegistry` bean (auto-config ordered after Boot metrics) ⇒ customizer adds `TachyonMetricsListener` (timer `mcp.server.operations`, tags method + snake-case outcome; starter-built server only) and `TachyonMeterBinder` feature gauges ([MetricsConfiguration](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java), [TachyonMetricsListener#complete](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonMetricsListener.java), [TachyonMeterBinder#bindTo](../../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonMeterBinder.java)). Listener uses `@InternalApi` `ObservationListener` → [[api-stability]].
- Actuator conditions and backoff covered by [TachyonActuatorAutoConfigurationTest](../../integrations/tachyon-spring-boot-starter/src/test/java/dev/tachyonmcp/spring/boot/TachyonActuatorAutoConfigurationTest.java).

Related: [[configuration]], [[extensions]], [[integrations]].
