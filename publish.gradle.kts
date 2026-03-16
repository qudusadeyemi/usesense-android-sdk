// UseSense SDK - Maven Central & GitHub Packages Publishing Configuration
//
// Required environment variables / Gradle properties for publishing:
//
//   ORG_GRADLE_PROJECT_signingKey        - GPG private key (ASCII armored)
//   ORG_GRADLE_PROJECT_signingPassword   - GPG key passphrase
//   ORG_GRADLE_PROJECT_sonatypeUsername  - Sonatype OSSRH username
//   ORG_GRADLE_PROJECT_sonatypePassword  - Sonatype OSSRH password
//   GITHUB_ACTOR                         - GitHub username (auto-provided in Actions)
//   GITHUB_TOKEN                         - GitHub token (auto-provided in Actions)

apply(plugin = "maven-publish")
apply(plugin = "signing")

val sdkVersion: String by project

afterEvaluate {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "ai.usesense"
                artifactId = "sdk"
                version = sdkVersion

                pom {
                    name.set("UseSense Android SDK")
                    description.set("Native Android SDK for human presence verification. Verify real humans, detect deepfakes, and prevent identity fraud.")
                    url.set("https://github.com/qudusadeyemi/usesense-android-sdk")

                    licenses {
                        license {
                            name.set("Proprietary")
                            url.set("https://github.com/qudusadeyemi/usesense-android-sdk/blob/main/LICENSE")
                        }
                    }

                    developers {
                        developer {
                            id.set("usesense")
                            name.set("UseSense")
                            email.set("support@usesense.ai")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/qudusadeyemi/usesense-android-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com/qudusadeyemi/usesense-android-sdk.git")
                        url.set("https://github.com/qudusadeyemi/usesense-android-sdk")
                    }
                }
            }
        }

        repositories {
            // Maven Central via Sonatype OSSRH
            maven {
                name = "sonatype"
                val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (sdkVersion.endsWith("-SNAPSHOT")) snapshotsUrl else releasesUrl

                credentials {
                    username = findProperty("sonatypeUsername") as String?
                        ?: System.getenv("ORG_GRADLE_PROJECT_sonatypeUsername")
                    password = findProperty("sonatypePassword") as String?
                        ?: System.getenv("ORG_GRADLE_PROJECT_sonatypePassword")
                }
            }

            // GitHub Packages (fallback)
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

    configure<SigningExtension> {
        val signingKey = findProperty("signingKey") as String?
            ?: System.getenv("ORG_GRADLE_PROJECT_signingKey")
        val signingPassword = findProperty("signingPassword") as String?
            ?: System.getenv("ORG_GRADLE_PROJECT_signingPassword")

        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(the<PublishingExtension>().publications["release"])
        }
    }
}
