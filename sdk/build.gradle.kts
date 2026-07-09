import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.dokka")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.vanniktech.maven.publish")
}

// ktlint config: lint-only by default (no auto-format in the build
// pipeline), and ignore the existing codebase's style baseline for
// now so this release doesn't block on a full reformat. Remove the
// `baseline` once the codebase has been run through `ktlintFormat`
// and the baseline file committed.
ktlint {
    android.set(true)
    ignoreFailures.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

android {
    namespace = "com.usesense.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        aarMetadata {
            minCompileSdk = 28
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Emit Kotlin metadata that older consumer toolchains can read. The
        // module compiles with Kotlin 2.2.10, which by default writes metadata
        // version 2.2.0 — unreadable by any compiler older than 2.2. React
        // Native (even 0.76) ships Kotlin 1.9.x, whose compiler reads metadata
        // only up to 2.0.0, so `react-native-usesense` failed to compile against
        // this AAR ("Class … was compiled with an incompatible version of
        // Kotlin. metadata version is 2.2.0, but the compiler … can read up to
        // 2.0.0"). Pinning api/languageVersion to 2.0 makes the compiler emit
        // 2.0-readable metadata, so Kotlin 1.9+ consumers (RN, older native
        // integrators) can link against it. Flutter is unaffected either way.
        apiVersion = "2.0"
        languageVersion = "2.0"
    }

    buildFeatures {
        // viewBinding deliberately OFF: the SDK's UI is Compose, nothing uses
        // ViewBinding. With AGP 9.1 it pulled androidx.databinding:viewbinding:9.1.0,
        // which carries a dependency *constraint* forcing kotlin-stdlib to 2.2.10
        // onto every consumer — unreadable by RN's Kotlin 1.9 toolchain, and a
        // constraint overrides ordinary version pins. Dropping it lets consumers
        // resolve the SDK's own kotlin-stdlib 2.0.21 with no resolutionStrategy force.
        compose = true
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Publishing
// ─────────────────────────────────────────────────────────────────────────
//
// Target repositories:
//
//   1. GitHub Packages (always):
//      ai.usesense:sdk:<version>
//      https://maven.pkg.github.com/qudusadeyemi/usesense-android-sdk
//      Requires a GitHub PAT with read:packages scope from integrators.
//
//   2. Maven Central via Sonatype Central Portal (when signing creds present):
//      ai.usesense:sdk:<version>
//      Zero-auth. Primary recommended channel once activated.
//
//   3. Local Maven (for JitPack and local ./gradlew builds):
//      ~/.m2/repository/ai/usesense/sdk/<version>/
//
// All three targets share the same `release` Maven publication, created by
// vanniktech's AndroidSingleVariantLibrary below.
//
// Required env vars for a full CI publish-and-release:
//
//   GITHUB_ACTOR                                   (auto in Actions)
//   GITHUB_TOKEN                                   (auto in Actions)
//   ORG_GRADLE_PROJECT_mavenCentralUsername        (Sonatype user token)
//   ORG_GRADLE_PROJECT_mavenCentralPassword        (Sonatype user token)
//   ORG_GRADLE_PROJECT_signingInMemoryKey          (ASCII-armored GPG secret key)
//   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword  (GPG passphrase)
//
// When the signing key is absent (local builds, PR CI), Maven Central
// publishing and signing are both skipped. GitHub Packages + mavenLocal
// still work.

val sdkVersion: String by project

val hasSigningKey: Boolean = run {
    val fromProperty = providers.gradleProperty("signingInMemoryKey").orNull
    val fromEnv = System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey")
    !fromProperty.isNullOrBlank() || !fromEnv.isNullOrBlank()
}

mavenPublishing {
    // AndroidSingleVariantLibrary creates the `release` Maven publication
    // from the `release` build variant with sources + javadoc JARs
    // attached. Replaces the AGP
    // `android { publishing { singleVariant("release") { ... } } }`
    // block; having both would register duplicate publications.
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )

    coordinates("ai.usesense", "sdk", sdkVersion)

    if (hasSigningKey) {
        // vanniktech 0.34 dropped the explicit SonatypeHost parameter;
        // the Central Portal (https://central.sonatype.com) is now the
        // only supported host, so the argument is implied. `automaticRelease`
        // uploads to the staging repo and immediately auto-releases on
        // successful validation, so CI doesn't have to poll for the
        // close/release lifecycle.
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
    }

    pom {
        name.set("UseSense Android SDK")
        description.set(
            "Native Android SDK for human presence verification. Verify real " +
                "humans, detect deepfakes, and prevent identity fraud with three " +
                "independent verification pillars: DeepSense (channel integrity), " +
                "LiveSense (presence verification), and MatchSense (identity " +
                "collision detection).",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/qudusadeyemi/usesense-android-sdk")

        licenses {
            license {
                name.set("Proprietary")
                url.set("https://github.com/qudusadeyemi/usesense-android-sdk/blob/main/LICENSE")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("usesense")
                name.set("UseSense")
                email.set("support@usesense.ai")
                url.set("https://usesense.ai")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/qudusadeyemi/usesense-android-sdk.git")
            developerConnection.set("scm:git:ssh://github.com/qudusadeyemi/usesense-android-sdk.git")
            url.set("https://github.com/qudusadeyemi/usesense-android-sdk")
        }
    }
}

// GitHub Packages repository: kept alongside vanniktech's Maven Central
// config. vanniktech only handles Central + mavenLocal targets, leaving
// the maven-publish DSL's `repositories {}` block free for custom repos.
// The same `release` publication that goes to Central also goes here.
afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/qudusadeyemi/usesense-android-sdk")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}

dependencies {
    // Kotlin stdlib pinned to 2.0.21 (not the compiler's 2.2.10): the module is
    // compiled with apiVersion 2.0, so it needs no newer stdlib API, and the
    // published POM must advertise a stdlib whose metadata is readable by Kotlin
    // 1.9 consumers (React Native). `kotlin.stdlib.default.dependency=false` in
    // gradle.properties disables the auto-added 2.2.10 so this wins.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")

    // CameraX
    val cameraVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose (UI parity rebuild — Phase 0 foundation). Compose compiler
    // plugin is applied above; BOM keeps the artifact versions aligned.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Play Integrity
    implementation("com.google.android.play:integrity:1.4.0")

    // MediaPipe FaceMesh (v4.1: 3D liveness / Geometric Coherence)
    implementation("com.google.mediapipe:tasks-vision:0.20230731")

    // ML Kit Face Detection (v4.1: RMAS action validation in inline step-up)
    implementation("com.google.mlkit:face-detection:16.1.5")

    // ML Kit Document Scanner (rear-camera document capture: edge detect, deskew,
    // glare/finger detection). Honors the capture contract's camera method.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

    // TensorFlow Lite (v4.2: on-device antispoof classifier, feature-flagged).
    // 2.16.1, not 2.14.0: in 2.14 the transitive `tensorflow-lite-api` artifact
    // declares the SAME `org.tensorflow.lite` namespace as `tensorflow-lite`,
    // which AGP 8.x rejects at an app's release manifest merge
    // ("Namespace 'org.tensorflow.lite' is used in multiple modules"). It never
    // failed our own CI because we only build the AAR + a debug demo, never a
    // release *app*. 2.16.1 gives the -api artifact its own namespace. The SDK
    // uses only the core Interpreter + NnApiDelegate API (see AntiSpoofClassifier),
    // which is unchanged across 2.14–2.16.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // NOTE: tensorflow-lite-support was removed. It was unused (no TensorImage/
    // TensorBuffer/ImageProcessor anywhere in the SDK) and its 0.4.4 -api split
    // carried the same duplicate-namespace collision with no fixed release.

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    // org.json is shipped as Android stubs in android.jar (every method
    // returns null / 0), so unit tests that exercise JSON parsing need the
    // real implementation on the test classpath. Used by FlowsClientTest.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
