pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tunguska"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":app",
    ":core:crypto",
    ":core:domain",
    ":core:netpolicy",
    ":core:storage",
    ":engine:api",
    ":engine:singbox",
    ":security:audit",
    ":trafficprobe",
    ":tools:ingest",
    ":vpnservice",
)
