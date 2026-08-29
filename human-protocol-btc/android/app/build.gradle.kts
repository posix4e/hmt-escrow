plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "org.hpb.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.hpb.app"
        minSdk = 28 // ChaCha20 (NIP-44) needs API 28+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
}

configurations.all {
    // the JVM secp binding must not reach the APK
    exclude(group = "fr.acinq.secp256k1", module = "secp256k1-kmp-jni-jvm")
}
