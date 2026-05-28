rootProject.name = "burpmind"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Auto-download a matching JDK via the Foojay Disco API whenever the build's
// declared Java toolchain (see root build.gradle.kts: jvmToolchain(21)) isn't
// already installed on the developer's machine. Lets `./gradlew build` work on
// any host with any JDK installed, as long as Gradle itself can launch.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

include(
    ":core-domain",
    ":core-app",
    ":core-infra",
    ":ui-swing",
    ":adapter-burp",
)
