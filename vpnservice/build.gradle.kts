plugins {
    alias(libs.plugins.android.library)
}

val androidCompileSdk = providers.gradleProperty("tunguska.android.compileSdk").get().toInt()
val androidMinSdk = providers.gradleProperty("tunguska.android.minSdk").get().toInt()

android {
    namespace = "io.acionyx.tunguska.vpnservice"
    compileSdk = androidCompileSdk

    defaultConfig {
        minSdk = androidMinSdk
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.engine.api)
    implementation(projects.core.domain)
    implementation(projects.security.audit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.libbox.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(projects.engine.singbox)
}
