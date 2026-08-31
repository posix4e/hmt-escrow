plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":roles"))
    implementation(project(":headless"))
}

application {
    mainClass = "org.hpb.cvat.CvatBridgeKt"
}

// A fake CVAT to try the bridge against without a deployment:
//   gradle :cvat:mock   then   CVAT_URL=http://127.0.0.1:7688 gradle :cvat:run
tasks.register<JavaExec>("mock") {
    group = "application"
    description = "Serve the built-in mock CVAT (task 1 'animals', labels cat/dog)"
    mainClass = "org.hpb.cvat.MockCvatKt"
    classpath = sourceSets["main"].runtimeClasspath
}

// Host a live CVAT job for a human worker:
//   CVAT_URL=… CVAT_TOKEN=… HPB_RELAYS=… gradle :cvat:liveJob
tasks.register<JavaExec>("liveJob") {
    group = "application"
    description = "Publish one CVAT job and wait for a worker to finish it"
    mainClass = "org.hpb.cvat.CvatLiveJobKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
    environment(System.getenv())
}
