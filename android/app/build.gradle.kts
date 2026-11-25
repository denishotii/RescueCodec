plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.rescuecodec.app"
    // Meshtastic SDK 0.2.0 needs compileSdk 37 and Compose 1.13 needs 37.1. targetSdk stays 35.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "org.rescuecodec.app"
        minSdk = 26
        targetSdk = 35
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
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":codec"))
    implementation(project(":engine"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.meshtastic.sdk.core)
    implementation(libs.meshtastic.sdk.transport.ble)
    implementation(libs.meshtastic.sdk.transport.tcp)
}
