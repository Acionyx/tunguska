plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.acionyx.tunguska.trafficprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.acionyx.tunguska.trafficprobe"
        minSdk = 26
        targetSdk = 36
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
