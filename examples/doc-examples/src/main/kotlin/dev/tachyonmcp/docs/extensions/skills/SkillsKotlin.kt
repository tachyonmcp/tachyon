package dev.tachyonmcp.docs.extensions.skills

// snips-start: skills_kotlin
import dev.tachyonmcp.extensions.skills.ClasspathSkillsRegistry
import dev.tachyonmcp.extensions.skills.FilesystemSkillsRegistry
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.config.skills
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

fun main() {
    val server = TachyonServer(port = 8080) {
        skills(FilesystemSkillsRegistry(Path.of("skills"))) {
            registry(ClasspathSkillsRegistry("bundled-skills"))
            cacheTtl = 5.minutes
            cacheScope = "public"
        }
    }
}
// snips-end: skills_kotlin
