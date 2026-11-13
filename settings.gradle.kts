import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "claude-comment-tasks"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
    }
}

plugins {
    // Provisions the JDK required by the target IntelliJ Platform (JDK 25 for 2026.2+).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
