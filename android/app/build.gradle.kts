plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "org.hpb.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.hpb.app"
        minSdk = 28 // ChaCha20 (NIP-44) needs API 28+
        targetSdk = 35 // Play requires 35+ for new apps/updates since 2025-08
        // the release workflow feeds these from the run number and tag
        versionCode = (System.getenv("HPB_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HPB_VERSION_NAME") ?: "0.1.0"
    }

    // Store uploads are signed with the CI keystore (env-provided); local
    // and PR builds see no keystore and stay unsigned.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    // The Android-portable core (protocol, sessions, OkHttp relay client) —
    // substituted from the composite ../kotlin build.
    implementation("org.hpb:androidcore:0.1.0")
    // libsecp256k1 JNI for Android replaces the JVM artifact at runtime.
    runtimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.15.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Credentials are held in a Keystore-backed store, not plain preferences —
    // the worker key is this worker's identity and its loss costs money.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

configurations.all {
    // the JVM secp binding must not reach the APK
    exclude(group = "fr.acinq.secp256k1", module = "secp256k1-kmp-jni-jvm")
}
