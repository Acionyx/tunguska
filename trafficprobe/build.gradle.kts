plugins {
    alias(libs.plugins.android.application)
}

val androidCompileSdk = providers.gradleProperty("tunguska.android.compileSdk").get().toInt()
val androidMinSdk = providers.gradleProperty("tunguska.android.minSdk").get().toInt()
val androidTargetSdk = providers.gradleProperty("tunguska.android.targetSdk").get().toInt()

android {
    namespace = "io.acionyx.tunguska.trafficprobe"
    compileSdk = androidCompileSdk

    defaultConfig {
        applicationId = "io.acionyx.tunguska.trafficprobe"
        minSdk = androidMinSdk
        targetSdk = androidTargetSdk
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

