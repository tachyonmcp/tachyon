Restore after Tachyon 1.1 is published; the pinned 1.0.1 lacks the API these use.

- `RedisSessionStore.java` → `src/main/java/dev/tachyonmcp/docs/springboot/reference/`: implements the 1.1 `SessionStore#create(key, ownerKey, expiresAt)`.
- `LivenessOptions.java` → `src/main/java/dev/tachyonmcp/docs/running/configuration/` and `KotlinLiveness.kt` → `src/main/kotlin/dev/tachyonmcp/docs/running/configuration/`: use the 1.1 `NetworkConfig.Builder#liveness`. Repoint the `snips` markers in `docs/running/configuration.md`. Keep the Kotlin helper's name off `liveness`: inside `network { }` a same-named `TachyonServerBuilder` extension shadows a missing scope member.
