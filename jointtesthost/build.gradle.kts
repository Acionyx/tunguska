plugins {
    alias(libs.plugins.android.application)
}

val androidCompileSdk = providers.gradleProperty("tunguska.android.compileSdk").get().toInt()
val androidMinSdk = providers.gradleProperty("tunguska.android.minSdk").get().toInt()
val androidTargetSdk = providers.gradleProperty("tunguska.android.targetSdk").get().toInt()

android {
    namespace = "io.acionyx.tunguska.jointtesthost"
    compileSdk = androidCompileSdk

    defaultConfig {
        applicationId = "io.acionyx.tunguska.jointtesthost"
        minSdk = androidMinSdk
        targetSdk = androidTargetSdk
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
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

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
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

dependencies {
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)

    testImplementation(libs.junit4)
}