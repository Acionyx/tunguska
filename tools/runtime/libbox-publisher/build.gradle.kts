import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    base
    `maven-publish`
}

fun requiredProperty(name: String): String =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Missing required -P$name value.")

val libboxAarPath = requiredProperty("libboxAarPath")
val libboxGroupId = requiredProperty("libboxGroupId")
val libboxArtifactId = requiredProperty("libboxArtifactId")
val libboxVersion = requiredProperty("libboxVersion")
val libboxPomName = providers.gradleProperty("libboxPomName").orNull ?: libboxArtifactId
val libboxPomDescription = providers.gradleProperty("libboxPomDescription").orNull
    ?: "Pinned sing-box libbox Android runtime for Tunguska."
val publishRepositoryUrl = requiredProperty("publishRepositoryUrl")
val publishRepositoryUser = providers.gradleProperty("publishRepositoryUser").orNull
val publishRepositoryPassword = providers.gradleProperty("publishRepositoryPassword").orNull

val validatePublicationInputs = tasks.register("validatePublicationInputs") {
    doLast {
        val artifactFile = file(libboxAarPath)
        if (!artifactFile.isFile) {
            throw GradleException("libbox AAR does not exist at '$libboxAarPath'.")
        }
        if (publishRepositoryUser.isNullOrBlank() xor publishRepositoryPassword.isNullOrBlank()) {
            throw GradleException(
                "publishRepositoryUser and publishRepositoryPassword must either both be set or both be omitted."
            )
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("libbox") {
            groupId = libboxGroupId
            artifactId = libboxArtifactId
            version = libboxVersion
            artifact(file(libboxAarPath)) {
                extension = "aar"
            }
            pom {
                name.set(libboxPomName)
                description.set(libboxPomDescription)
            }
        }
    }
    repositories {
        maven {
            name = "target"
            url = uri(publishRepositoryUrl)
            if (!publishRepositoryUser.isNullOrBlank()) {
                credentials {
                    username = publishRepositoryUser
                    password = publishRepositoryPassword
                }
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(validatePublicationInputs)
}
