// UseSense SDK - GitHub Packages Publishing Configuration
//
// Required environment variables / Gradle properties for publishing:
//
//   GITHUB_ACTOR  - GitHub username (auto-provided in Actions)
//   GITHUB_TOKEN  - GitHub token (auto-provided in Actions)

apply(plugin = "maven-publish")

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
