import dev.tachyonmcp.docs.build.tachyon

// snips-start: kotlin_gradle_build
plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("dev.tachyonmcp:tachyon-bom:${tachyon.version}"))
    implementation("dev.tachyonmcp:tachyon-kotlin")
}

kotlin { jvmToolchain(21) }

application { mainClass = "MyMcpServerKt" }
// snips-end: kotlin_gradle_build
