plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":protocol"))
    implementation(project(":roles"))
    implementation(project(":headless"))
    implementation(project(":androidcore"))
    implementation(project(":labeler"))
    implementation(project(":cvat"))

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

// Pins CvatClient against a real CVAT server; the mock cannot validate our own
// API guesses. Needs CVAT_URL and CVAT_TOKEN — see deploy/cvat/README.md.
tasks.register<Test>("realCvatTest") {
    description = "Runs the CvatClient conformance test against a real CVAT deployment."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*RealCvatClientTest") }
    listOf("CVAT_URL", "CVAT_TOKEN").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    outputs.upToDateWhen { false }
}
