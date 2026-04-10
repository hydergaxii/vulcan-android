plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vulcan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vulcan.app"
        minSdk = 26          // Android 8.0 — 98% of active devices
        targetSdk = 35       // Android 15
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    // Native libraries (bundled ARM64 binaries)
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Split APKs for size optimization
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")      // Primary: modern ARM64
            include("armeabi-v7a")    // Fallback: older 32-bit ARM
            isUniversalApk = true
        }
    }
}

dependencies {
    // ── Compose ──────────────────────────────────────────────────────────
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.runtime.livedata)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // ── Navigation ────────────────────────────────────────────────────────
    implementation(libs.navigation.compose)

    // ── Hilt DI ───────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // ── Room Database ─────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── Lifecycle ─────────────────────────────────────────────────────────
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    // ── WorkManager ───────────────────────────────────────────────────────
    implementation(libs.work.runtime.ktx)

    // ── Networking ────────────────────────────────────────────────────────
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    // ── Image Loading ─────────────────────────────────────────────────────
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // ── Coroutines ────────────────────────────────────────────────────────
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    // ── Serialization ─────────────────────────────────────────────────────
    implementation(libs.serialization.json)

    // ── Security ──────────────────────────────────────────────────────────
    implementation(libs.biometric)
    implementation(libs.security.crypto)
    implementation(libs.bouncycastle)

    // ── Web Server (NanoHTTPD — Vulcan Web Dashboard) ─────────────────────
    implementation(libs.nanohttpd)

    // ── Cloud Backup (MinIO S3) ────────────────────────────────────────────
    implementation(libs.minio)

    // ── Accompanist ───────────────────────────────────────────────────────
    implementation(libs.accompanist.permissions)
    implementation(libs.accompanist.systemuicontroller)

    // ── Android Core ──────────────────────────────────────────────────────
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.gson)

    // ── Testing ───────────────────────────────────────────────────────────
    testImplementation(libs.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

// ── Room schema export directory ──────────────────────────────────────────────
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
