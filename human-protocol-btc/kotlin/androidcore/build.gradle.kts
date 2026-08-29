plugins {
    kotlin("jvm")
    `java-library`
}

// Android-portable core: everything the Compose shell needs, testable on the
// JVM. Uses OkHttp (Android-compatible) instead of java.net.http.
dependencies {
    api(project(":protocol"))
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
