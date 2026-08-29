plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":protocol"))
    implementation(project(":roles"))
    implementation(project(":headless"))
    implementation(project(":androidcore"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Integration tests spawn bitcoind -regtest; they are the default test task of
// this module (kept out of :engine so unit tests stay process-free).
tasks.test {
    // Fail fast with a clear message if bitcoind is missing.
    environment("BITCOIND_EXE", System.getenv("BITCOIND_EXE") ?: "bitcoind")
}
