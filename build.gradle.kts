plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false

    // Dokka: generates HTML API reference from KDoc comments. Applied in
    // sdk/build.gradle.kts; the release workflow packages the output as
    // `usesense-sdk-<version>-dokka.zip` and attaches it to the GitHub
    // Release. Dokka 2.x is the version that works with Kotlin 2.2.
    id("org.jetbrains.dokka") version "2.0.0" apply false

    // ktlint: Kotlin code style enforcement. Applied in sdk/build.gradle.kts
    // only (example app is developer-facing and we don't want to block
    // local builds of the demo on style). The CI workflow runs
    // `:sdk:ktlintCheck` on every PR.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}
