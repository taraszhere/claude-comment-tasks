import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

val platformVersion = providers.gradleProperty("platformVersion").get()
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").get()

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Unified IntelliJ IDEA distribution (2025.3+). Installer bundles the JetBrains Runtime for runIde.
        intellijIdea(platformVersion)
        testFramework(TestFrameworkType.Platform)
        // Java PSI is only needed by the platform tests (Java comments / string literals).
        testBundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Claude Comment Tasks"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = pluginSinceBuild
            // No until-build: compatible with all future builds (recommended for 243+).
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Verify against the oldest supported release line and the compile target.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = pluginSinceBuild
                untilBuild = "263.*"
            }
        }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Do not generate bridge methods for interface defaults (keeps Plugin Verifier free of false "overrides").
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

tasks {
    buildPlugin {
        archiveBaseName = "claude-comment-tasks"
    }

    test {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
