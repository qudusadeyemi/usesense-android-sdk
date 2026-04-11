pluginManagement {
    // Plugin versions are declared here (not in app/build.gradle.kts)
    // so Gradle's plugin resolution doesn't try to go through an
    // includeBuild classpath. Historically this example used
    // `includeBuild("..")` to consume the SDK as a composite build,
    // but that setup double-registered the Kotlin Android plugin
    // extension and failed at configuration time. Consuming the
    // published SDK from Maven Central is simpler, matches what a
    // real integrator sees, and sidesteps the extension-conflict
    // class of bugs entirely.
    plugins {
        id("com.android.application") version "9.1.0"
        id("org.jetbrains.kotlin.android") version "2.2.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "usesense-example"
include(":app")
