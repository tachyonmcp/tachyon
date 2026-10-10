import dev.tachyonmcp.docs.build.tachyon

// snips-start: kotlin_gradle_build
plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories { mavenCentral() }

val tachyonVersion = project.properties["tachyonVersion"]

dependencies {
    implementation(platform("dev.tachyonmcp:tachyon-bom:$tachyonVersion"))
    implementation("dev.tachyonmcp:tachyon-kotlin")
}

kotlin { jvmToolchain(21) }

application { mainClass = "MyMcpServerKt" }
// snips-end: kotlin_gradle_build
