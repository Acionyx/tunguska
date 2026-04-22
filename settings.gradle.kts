import java.util.Locale

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

val githubPackagesUser = providers.gradleProperty("githubPackagesUser")
    .orElse(providers.environmentVariable("GITHUB_PACKAGES_USER"))
    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubPackagesToken = providers.gradleProperty("githubPackagesToken")
    .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
val hasGitHubPackagesCredentials = githubPackagesUser.isPresent && githubPackagesToken.isPresent
val localLibboxRepo = rootDir.resolve(".tmp/maven")
val hasLocalLibboxRepo = localLibboxRepo.isDirectory

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "tunguskaLibboxLocal"
            url = uri(localLibboxRepo)
            content {
                includeGroup("io.acionyx.thirdparty")
            }
        }
        if (hasGitHubPackagesCredentials) {
            maven {
                name = "tunguskaGitHubPackages"
                url = uri("https://maven.pkg.github.com/${"Acionyx".lowercase(Locale.ROOT)}/tunguska")
                credentials {
                    username = githubPackagesUser.get()
                    password = githubPackagesToken.get()
                }
                content {
                    includeGroup("io.acionyx.thirdparty")
                }
            }
        }
    }
}

gradle.settingsEvaluated {
    if (!hasGitHubPackagesCredentials && !hasLocalLibboxRepo) {
        logger.warn(
            "No GitHub Packages credentials were configured for io.acionyx.thirdparty " +
                "and no local .tmp/maven libbox cache exists. Configure githubPackagesUser/" +
                "githubPackagesToken (or GITHUB_PACKAGES_USER/GITHUB_PACKAGES_TOKEN) " +
                "or run tools/runtime/fetch-singbox-embedded.ps1 for a local override."
        )
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
