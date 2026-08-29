plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":engine"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
