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
        maven {
            name = "tunguskaLibbox"
            url = uri(rootDir.resolve(".tmp/maven"))
            content {
                includeGroup("io.acionyx.thirdparty")
            }
        }
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
    ":jointtesthost",
    ":security:audit",
    ":trafficprobe",
    ":tools:ingest",
    ":vpnservice",
)
