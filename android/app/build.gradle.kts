// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Vosk ships a native library per ABI. Phones are arm64 or armv7; the emulator on Apple silicon is arm64.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
    }

    sourceSets {
        // Room writes its schema here so a schema change shows up in review.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas", "$projectDir/../../sim/e2e")
    }
}

kotlin {
    jvmToolchain(17)
}

// Speech models (Vosk). The default APK is slim: it has no models, and the phone gets them with
// scripts/install-vosk-models.sh (adb push into the app's own folder) or from a file in Settings.
// `./gradlew :app:assembleDebug -PbundleVosk=true` builds the full APK (about +85 MB) with both
// small models inside, so it works offline right after install. See docs/VOICE.md.
val bundleVosk = providers.gradleProperty("bundleVosk").map { it.toBoolean() }.orElse(false).get()
if (bundleVosk) {
    val voskSrc = layout.projectDirectory.dir("../../assets-offline/vosk")
    val voskOut = layout.buildDirectory.dir("generated/voskAssets")
    val prepareVosk = tasks.register<Sync>("prepareVoskAssets") {
        from(voskSrc.dir("vosk-model-small-de-0.15")) { into("vosk/de") }
        from(voskSrc.dir("vosk-model-small-en-us-0.15")) { into("vosk/en") }
        into(voskOut)
    }
    android.sourceSets.getByName("main").assets.srcDir(voskOut)
    tasks.matching { it.name.matches(Regex("(merge|generate).*Assets")) }.configureEach { dependsOn(prepareVosk) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.meshtastic.sdk.core)
    implementation(libs.meshtastic.sdk.transport.ble)
    implementation(libs.meshtastic.sdk.transport.tcp)
    implementation(libs.meshtastic.sdk.storage)
    implementation(libs.kable.core)

    implementation(libs.vosk.android)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.meshtastic.sdk.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The Meshtastic SDK jars are built for Java 21, so the unit tests must run on a JDK 21 or newer.
// The app itself still compiles for Java 17. Set RESCUECODEC_TEST_JAVA_HOME to a JDK 21+ if yours is elsewhere.
val testJavaHome: String? = listOfNotNull(
    System.getenv("RESCUECODEC_TEST_JAVA_HOME"),
    "/opt/homebrew/opt/openjdk@25",
    "/opt/homebrew/opt/openjdk@21",
    "/opt/homebrew/opt/openjdk",
    "/usr/lib/jvm/java-21-openjdk-amd64",
    "/usr/lib/jvm/java-25-openjdk-amd64",
).firstOrNull { File(it, "bin/java").exists() }

tasks.withType<Test>().configureEach {
    if (testJavaHome != null) executable = "$testJavaHome/bin/java"
}
