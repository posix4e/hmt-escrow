plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
    `java-library`
}

dependencies {
    implementation("fr.acinq.secp256k1:secp256k1-kmp:0.15.0")
    runtimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.15.0")
    // Rpc exposes JsonElement in its public API, so consumers need it too.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
