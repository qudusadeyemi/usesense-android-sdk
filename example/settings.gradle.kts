pluginManagement {
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

// Include the SDK from the parent project for local development
includeBuild("..") {
    dependencySubstitution {
        substitute(module("ai.usesense:sdk")).using(project(":sdk"))
    }
}
