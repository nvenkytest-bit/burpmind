rootProject.name = "burpmind"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
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
